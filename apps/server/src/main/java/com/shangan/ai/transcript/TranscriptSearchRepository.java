package com.shangan.ai.transcript;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 视频转写只读检索边界；所有查询都显式限定媒体项，防止跨视频上下文泄漏。 */
@Repository
public class TranscriptSearchRepository {
  private final JdbcClient jdbc;

  public TranscriptSearchRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 启动时主动验证 FTS5 表可查询；不可用时应用不会错误地提供 READY 视频问答。 */
  @PostConstruct
  void verifyFts5() {
    jdbc.sql("select count(*) from transcript_segments_fts").query(Long.class).single();
  }

  public List<TranscriptMatch> search(String mediaItemId, String query, int limit) {
    String ftsQuery = toFtsQuery(query);
    if (ftsQuery.isBlank()) return List.of();
    int safeLimit = Math.max(1, Math.min(limit, 50));
    return jdbc.sql(
            """
            select s.id,s.start_ms,s.end_ms,s.text,bm25(transcript_segments_fts) relevance
              from transcript_segments_fts
              join transcript_segments s on s.rowid=transcript_segments_fts.rowid
             where transcript_segments_fts match :query and s.media_item_id=:mediaId
             order by relevance,s.segment_index
             limit :limit
            """)
        .param("query", ftsQuery)
        .param("mediaId", mediaItemId)
        .param("limit", safeLimit)
        .query(
            (row, number) ->
                new TranscriptMatch(
                    row.getString("id"),
                    row.getLong("start_ms"),
                    row.getLong("end_ms"),
                    row.getString("text"),
                    row.getDouble("relevance")))
        .list();
  }

  public List<TranscriptMatch> around(String mediaItemId, long positionMs, long radiusMs) {
    long start = Math.max(0, positionMs - Math.max(0, radiusMs));
    long end = positionMs + Math.max(0, radiusMs);
    return jdbc.sql(
            """
            select id,start_ms,end_ms,text,0.0 relevance
              from transcript_segments
             where media_item_id=:mediaId and end_ms>=:start and start_ms<=:end
             order by segment_index
            """)
        .param("mediaId", mediaItemId)
        .param("start", start)
        .param("end", end)
        .query(
            (row, number) ->
                new TranscriptMatch(
                    row.getString("id"),
                    row.getLong("start_ms"),
                    row.getLong("end_ms"),
                    row.getString("text"),
                    row.getDouble("relevance")))
        .list();
  }

  public List<SectionSummary> sectionsOverlapping(String mediaItemId, long startMs, long endMs) {
    return jdbc.sql(
            """
            select section_index,start_ms,end_ms,summary
              from video_section_summaries
             where media_item_id=:mediaId and end_ms>=:start and start_ms<=:end
             order by section_index
            """)
        .param("mediaId", mediaItemId)
        .param("start", Math.max(0, startMs))
        .param("end", Math.max(startMs, endMs))
        .query(
            (row, number) ->
                new SectionSummary(
                    row.getInt("section_index"),
                    row.getLong("start_ms"),
                    row.getLong("end_ms"),
                    row.getString("summary")))
        .list();
  }

  public Optional<VideoSummary> summary(String mediaItemId) {
    return jdbc.sql(
            "select summary,outline_json,model_name,generated_at from video_summaries where media_item_id=:mediaId")
        .param("mediaId", mediaItemId)
        .query(
            (row, number) ->
                new VideoSummary(
                    row.getString("summary"),
                    row.getString("outline_json"),
                    row.getString("model_name"),
                    row.getLong("generated_at")))
        .optional();
  }

  private String toFtsQuery(String query) {
    if (query == null || query.isBlank()) return "";
    return java.util.Arrays.stream(query.trim().split("\\s+"))
        .filter(term -> !term.isBlank())
        .map(term -> "\"" + term.replace("\"", "\"\"") + "\"")
        // 视频问答需要让分散在不同片段的关键词分别参与 Top 8 排序，不能要求同段全命中。
        .collect(java.util.stream.Collectors.joining(" OR "));
  }

  public record TranscriptMatch(
      String segmentId, long startMs, long endMs, String text, double relevance) {}

  public record SectionSummary(int sectionIndex, long startMs, long endMs, String summary) {}

  public record VideoSummary(
      String summary, String outlineJson, String modelName, long generatedAtEpochMs) {}
}
