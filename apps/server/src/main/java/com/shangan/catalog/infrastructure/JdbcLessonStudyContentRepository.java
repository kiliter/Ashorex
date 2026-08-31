package com.shangan.catalog.infrastructure;

import com.shangan.catalog.domain.LessonStudyContent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用 JdbcClient 读取和批量覆盖每集课程学习内容。 */
@Repository
public class JdbcLessonStudyContentRepository implements LessonStudyContentRepository {

  private final JdbcClient jdbc;

  public JdbcLessonStudyContentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<LessonStudyContent> findByMediaItemId(String mediaItemId) {
    return jdbc.sql("select * from lesson_study_contents where media_item_id = :mediaItemId")
        .param("mediaItemId", mediaItemId)
        .query(this::mapContent)
        .optional();
  }

  @Override
  public Map<String, Instant> findUpdatedAtByCourseId(String courseId) {
    List<UpdatedAtRow> rows =
        jdbc.sql(
                "select content.media_item_id, content.updated_at "
                    + "from lesson_study_contents content "
                    + "join media_items media on media.id = content.media_item_id "
                    + "where media.course_id = :courseId")
            .param("courseId", courseId)
            .query(
                (row, number) ->
                    new UpdatedAtRow(
                        row.getString("media_item_id"),
                        Instant.ofEpochMilli(row.getLong("updated_at"))))
            .list();
    Map<String, Instant> result = new LinkedHashMap<>();
    rows.forEach(row -> result.put(row.mediaItemId(), row.updatedAt()));
    return Map.copyOf(result);
  }

  @Override
  public long count() {
    return jdbc.sql("select count(*) from lesson_study_contents").query(Long.class).single();
  }

  /** 在调用方事务内逐条 Upsert；冲突时保留首次导入时间和原主键。 */
  @Override
  public void upsertAll(List<LessonStudyContent> contents) {
    for (LessonStudyContent content : contents) {
      jdbc.sql(
              """
              insert into lesson_study_contents (
                id, media_item_id, full_text, summary_markdown, imported_at, updated_at
              ) values (
                :id, :mediaItemId, :fullText, :summaryMarkdown, :importedAt, :updatedAt
              )
              on conflict(media_item_id) do update set
                full_text = excluded.full_text,
                summary_markdown = excluded.summary_markdown,
                updated_at = excluded.updated_at
              """)
          .param("id", content.id())
          .param("mediaItemId", content.mediaItemId())
          .param("fullText", content.fullText())
          .param("summaryMarkdown", content.summaryMarkdown())
          .param("importedAt", content.importedAt().toEpochMilli())
          .param("updatedAt", content.updatedAt().toEpochMilli())
          .update();
    }
  }

  /** 将数据库 Epoch Milliseconds 映射为不可变领域数据。 */
  private LessonStudyContent mapContent(ResultSet row, int rowNumber) throws SQLException {
    return new LessonStudyContent(
        row.getString("id"),
        row.getString("media_item_id"),
        row.getString("full_text"),
        row.getString("summary_markdown"),
        Instant.ofEpochMilli(row.getLong("imported_at")),
        Instant.ofEpochMilli(row.getLong("updated_at")));
  }

  /** 后台课时列表所需的轻量更新时间行。 */
  private record UpdatedAtRow(String mediaItemId, Instant updatedAt) {}
}
