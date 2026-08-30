package com.shangan.ai.transcript;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 转写流水线持久化实现；事务边界由 TranscriptionJobService 控制。 */
@Repository
class TranscriptionStore {
  private final JdbcClient jdbc;

  TranscriptionStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<JobRow> findJob(String id) {
    return jobQuery("where j.id=:value", id);
  }

  Optional<JobRow> findByMediaItem(String mediaItemId) {
    return jobQuery("where j.media_item_id=:value", mediaItemId);
  }

  Optional<JobRow> findActive() {
    return jdbc.sql(
            jobSelect()
                + " where j.status in ('PENDING','EXTRACTING_AUDIO','TRANSCRIBING','SUMMARIZING') limit 1")
        .query(this::mapJob)
        .optional();
  }

  List<JobRow> findAll() {
    return jdbc.sql(jobSelect() + " order by j.updated_at desc").query(this::mapJob).list();
  }

  void insertJob(String id, String mediaItemId, Instant now) {
    long timestamp = now.toEpochMilli();
    jdbc.sql(
            "insert into transcription_jobs "
                + "(id,media_item_id,status,attempt_count,created_at,updated_at) "
                + "values (:id,:mediaId,'PENDING',1,:now,:now)")
        .param("id", id)
        .param("mediaId", mediaItemId)
        .param("now", timestamp)
        .update();
  }

  void resetJob(String id, String mediaItemId, Instant now) {
    deleteArtifacts(mediaItemId);
    jdbc.sql(
            "update transcription_jobs set status='PENDING',attempt_count=attempt_count+1,"
                + "last_error=null,started_at=null,finished_at=null,updated_at=:now where id=:id")
        .param("now", now.toEpochMilli())
        .param("id", id)
        .update();
  }

  void updateStatus(String id, String status, Instant now) {
    String sql =
        switch (status) {
          case "EXTRACTING_AUDIO" ->
              "update transcription_jobs set status=:status,started_at=:now,finished_at=null,last_error=null,updated_at=:now where id=:id";
          case "READY" ->
              "update transcription_jobs set status=:status,finished_at=:now,last_error=null,updated_at=:now where id=:id";
          default -> "update transcription_jobs set status=:status,updated_at=:now where id=:id";
        };
    jdbc.sql(sql).param("status", status).param("now", now.toEpochMilli()).param("id", id).update();
  }

  void fail(String id, String safeError, Instant now) {
    jdbc.sql(
            "update transcription_jobs set status='FAILED',last_error=:error,finished_at=:now,updated_at=:now where id=:id")
        .param("error", safeError)
        .param("now", now.toEpochMilli())
        .param("id", id)
        .update();
  }

  void replaceSegments(
      String mediaItemId, List<StoredSegment> segments, Instant now, IdSupplier ids) {
    jdbc.sql("delete from transcript_segments where media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .update();
    for (StoredSegment segment : segments) {
      jdbc.sql(
              "insert into transcript_segments "
                  + "(id,media_item_id,segment_index,start_ms,end_ms,text,created_at) "
                  + "values (:id,:mediaId,:index,:start,:end,:text,:now)")
          .param("id", ids.next())
          .param("mediaId", mediaItemId)
          .param("index", segment.index())
          .param("start", segment.startMs())
          .param("end", segment.endMs())
          .param("text", segment.text())
          .param("now", now.toEpochMilli())
          .update();
    }
  }

  void replaceSummaries(
      String mediaItemId,
      VideoSummaryService.SummaryBundle summary,
      String outlineJson,
      IdSupplier ids) {
    jdbc.sql("delete from video_section_summaries where media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .update();
    jdbc.sql("delete from video_summaries where media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .update();
    for (VideoSummaryService.SectionResult section : summary.sections()) {
      jdbc.sql(
              "insert into video_section_summaries "
                  + "(id,media_item_id,section_index,start_ms,end_ms,summary,created_at) "
                  + "values (:id,:mediaId,:index,:start,:end,:summary,:now)")
          .param("id", ids.next())
          .param("mediaId", mediaItemId)
          .param("index", section.sectionIndex())
          .param("start", section.startMs())
          .param("end", section.endMs())
          .param("summary", section.summary())
          .param("now", summary.generatedAt().toEpochMilli())
          .update();
    }
    jdbc.sql(
            "insert into video_summaries "
                + "(id,media_item_id,summary,outline_json,model_name,generated_at) "
                + "values (:id,:mediaId,:summary,:outline,:model,:now)")
        .param("id", ids.next())
        .param("mediaId", mediaItemId)
        .param("summary", summary.globalSummary())
        .param("outline", outlineJson)
        .param("model", summary.modelName())
        .param("now", summary.generatedAt().toEpochMilli())
        .update();
  }

  long segmentCount(String mediaItemId) {
    return jdbc.sql("select count(*) from transcript_segments where media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .query(Long.class)
        .single();
  }

  long ftsCount(String mediaItemId) {
    return jdbc.sql(
            "select count(*) from transcript_segments_fts f join transcript_segments s on s.rowid=f.rowid where s.media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .query(Long.class)
        .single();
  }

  private void deleteArtifacts(String mediaItemId) {
    jdbc.sql("delete from video_summaries where media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .update();
    jdbc.sql("delete from video_section_summaries where media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .update();
    jdbc.sql("delete from transcript_segments where media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .update();
  }

  private Optional<JobRow> jobQuery(String clause, String value) {
    return jdbc.sql(jobSelect() + " " + clause)
        .param("value", value)
        .query(this::mapJob)
        .optional();
  }

  private String jobSelect() {
    return """
        select j.id,j.media_item_id,m.emby_item_id,m.title,j.status,j.attempt_count,
               j.last_error,j.started_at,j.finished_at,j.created_at,j.updated_at
          from transcription_jobs j join media_items m on m.id=j.media_item_id
        """;
  }

  private JobRow mapJob(java.sql.ResultSet row, int number) throws java.sql.SQLException {
    return new JobRow(
        row.getString("id"),
        row.getString("media_item_id"),
        row.getString("emby_item_id"),
        row.getString("title"),
        row.getString("status"),
        row.getInt("attempt_count"),
        row.getString("last_error"),
        nullableInstant(row, "started_at"),
        nullableInstant(row, "finished_at"),
        Instant.ofEpochMilli(row.getLong("created_at")),
        Instant.ofEpochMilli(row.getLong("updated_at")));
  }

  private Instant nullableInstant(java.sql.ResultSet row, String column)
      throws java.sql.SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : Instant.ofEpochMilli(value);
  }

  record JobRow(
      String id,
      String mediaItemId,
      String embyItemId,
      String title,
      String status,
      int attemptCount,
      String lastError,
      Instant startedAt,
      Instant finishedAt,
      Instant createdAt,
      Instant updatedAt) {}

  record StoredSegment(int index, long startMs, long endMs, String text) {}

  interface IdSupplier {
    String next();
  }
}
