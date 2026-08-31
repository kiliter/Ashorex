package com.shangan.ai.content.infrastructure;

import com.shangan.ai.content.domain.QuizGenerationDraft;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用确定性排序读写题目草稿，发布标记由应用服务事务统一提交。 */
@Repository
public class JdbcQuizGenerationDraftRepository implements QuizGenerationDraftRepository {

  private final JdbcClient jdbc;

  public JdbcQuizGenerationDraftRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void save(QuizGenerationDraft draft) {
    jdbc.sql(
            "insert into quiz_generation_drafts "
                + "(id,job_id,course_id,media_item_id,status,requested_question_count,created_at) "
                + "values (:id,:jobId,:courseId,:mediaItemId,:status,:count,:createdAt)")
        .param("id", draft.id())
        .param("jobId", draft.jobId())
        .param("courseId", draft.courseId())
        .param("mediaItemId", draft.mediaItemId())
        .param("status", draft.status().name())
        .param("count", draft.requestedQuestionCount())
        .param("createdAt", draft.createdAt().toEpochMilli())
        .update();
    for (QuizGenerationDraft.Item item : draft.items()) {
      jdbc.sql(
              "insert into quiz_generation_draft_items "
                  + "(id,draft_id,question_type,content,explanation,sort_order) "
                  + "values (:id,:draftId,:type,:content,:explanation,:sortOrder)")
          .param("id", item.id())
          .param("draftId", draft.id())
          .param("type", item.questionType())
          .param("content", item.content().trim())
          .param("explanation", item.explanation().trim())
          .param("sortOrder", item.sortOrder())
          .update();
      insertOptions(item);
    }
  }

  @Override
  public Optional<QuizGenerationDraft> findById(String draftId) {
    return jdbc.sql("select * from quiz_generation_drafts where id=:id")
        .param("id", draftId)
        .query(this::mapDraft)
        .optional();
  }

  @Override
  public List<QuizGenerationDraft> findByCourse(String courseId) {
    return jdbc.sql(
            "select * from quiz_generation_drafts where course_id=:courseId "
                + "order by created_at desc,id")
        .param("courseId", courseId)
        .query(this::mapDraft)
        .list();
  }

  @Override
  public boolean hasReadyDraft(String mediaItemId) {
    return jdbc.sql(
                "select exists(select 1 from quiz_generation_drafts "
                    + "where media_item_id=:mediaItemId and status='READY_FOR_REVIEW')")
            .param("mediaItemId", mediaItemId)
            .query(Integer.class)
            .single()
        == 1;
  }

  @Override
  public void markPublished(
      String draftId, Map<String, String> questionIdsByItemId, Instant publishedAt) {
    for (Map.Entry<String, String> entry : questionIdsByItemId.entrySet()) {
      jdbc.sql(
              "update quiz_generation_draft_items set published_question_id=:questionId "
                  + "where id=:itemId and draft_id=:draftId and published_question_id is null")
          .param("questionId", entry.getValue())
          .param("itemId", entry.getKey())
          .param("draftId", draftId)
          .update();
    }
    jdbc.sql(
            "update quiz_generation_drafts set status='PUBLISHED',published_at=:publishedAt "
                + "where id=:draftId and status='READY_FOR_REVIEW'")
        .param("publishedAt", publishedAt.toEpochMilli())
        .param("draftId", draftId)
        .update();
  }

  @Override
  @Transactional
  public void updateItem(QuizGenerationDraft.Item item) {
    jdbc.sql(
            "update quiz_generation_draft_items set question_type=:type,content=:content,"
                + "explanation=:explanation,sort_order=:sortOrder "
                + "where id=:id and published_question_id is null")
        .param("type", item.questionType())
        .param("content", item.content().trim())
        .param("explanation", item.explanation().trim())
        .param("sortOrder", item.sortOrder())
        .param("id", item.id())
        .update();
    jdbc.sql("delete from quiz_generation_draft_options where draft_item_id=:id")
        .param("id", item.id())
        .update();
    insertOptions(item);
  }

  @Override
  @Transactional
  public void deleteItem(String itemId) {
    jdbc.sql(
            "delete from quiz_generation_draft_items "
                + "where id=:id and published_question_id is null")
        .param("id", itemId)
        .update();
  }

  private void insertOptions(QuizGenerationDraft.Item item) {
    for (QuizGenerationDraft.Option option : item.options()) {
      jdbc.sql(
              "insert into quiz_generation_draft_options "
                  + "(id,draft_item_id,content,correct,sort_order) "
                  + "values (:id,:itemId,:content,:correct,:sortOrder)")
          .param("id", option.id())
          .param("itemId", item.id())
          .param("content", option.content().trim())
          .param("correct", option.correct() ? 1 : 0)
          .param("sortOrder", option.sortOrder())
          .update();
    }
  }

  private QuizGenerationDraft mapDraft(ResultSet row, int rowNumber) throws SQLException {
    String draftId = row.getString("id");
    List<QuizGenerationDraft.Item> items =
        jdbc.sql(
                "select * from quiz_generation_draft_items where draft_id=:draftId "
                    + "order by sort_order,id")
            .param("draftId", draftId)
            .query(this::mapItem)
            .list();
    return new QuizGenerationDraft(
        draftId,
        row.getString("job_id"),
        row.getString("course_id"),
        row.getString("media_item_id"),
        QuizGenerationDraft.Status.valueOf(row.getString("status")),
        row.getInt("requested_question_count"),
        Instant.ofEpochMilli(row.getLong("created_at")),
        instantOrNull(row, "published_at"),
        items);
  }

  private QuizGenerationDraft.Item mapItem(ResultSet row, int rowNumber) throws SQLException {
    String itemId = row.getString("id");
    List<QuizGenerationDraft.Option> options =
        jdbc.sql(
                "select * from quiz_generation_draft_options where draft_item_id=:itemId "
                    + "order by sort_order,id")
            .param("itemId", itemId)
            .query(
                (option, number) ->
                    new QuizGenerationDraft.Option(
                        option.getString("id"),
                        option.getString("content"),
                        option.getInt("correct") == 1,
                        option.getInt("sort_order")))
            .list();
    return new QuizGenerationDraft.Item(
        itemId,
        row.getString("question_type"),
        row.getString("content"),
        row.getString("explanation"),
        row.getInt("sort_order"),
        row.getString("published_question_id"),
        options);
  }

  private Instant instantOrNull(ResultSet row, String column) throws SQLException {
    Object value = row.getObject(column);
    return value == null ? null : Instant.ofEpochMilli(row.getLong(column));
  }
}
