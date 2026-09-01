package com.shangan.quiz.infrastructure;

import com.shangan.quiz.domain.Question;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用确定性排序读取题目，并在同一事务内保存尝试及全部答案。 */
@Repository
public class JdbcQuestionRepository implements QuestionRepository {
  private final JdbcClient jdbc;

  public JdbcQuestionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Question> findByMedia(String mediaItemId, boolean enabledOnly) {
    String enabled = enabledOnly ? " and enabled=1" : "";
    return jdbc.sql(
            "select * from questions where media_item_id=:mediaItemId"
                + enabled
                + " order by sort_order,id")
        .param("mediaItemId", mediaItemId)
        .query(this::mapQuestion)
        .list();
  }

  @Override
  public boolean hasEnabled(String mediaItemId) {
    return jdbc.sql(
                "select exists(select 1 from questions where media_item_id=:mediaItemId and enabled=1)")
            .param("mediaItemId", mediaItemId)
            .query(Integer.class)
            .single()
        == 1;
  }

  @Override
  public Map<String, Integer> countByCourse(String courseId) {
    Map<String, Integer> result = new LinkedHashMap<>();
    jdbc.sql(
            "select q.media_item_id,count(*) question_count from questions q "
                + "join media_items m on m.id=q.media_item_id "
                + "where m.course_id=:courseId group by q.media_item_id order by q.media_item_id")
        .param("courseId", courseId)
        .query(
            row -> {
              while (row.next()) {
                result.put(row.getString("media_item_id"), row.getInt("question_count"));
              }
              return result;
            });
    return Map.copyOf(result);
  }

  @Override
  public Optional<Question> findById(String questionId) {
    return jdbc.sql("select * from questions where id=:id")
        .param("id", questionId)
        .query(this::mapQuestion)
        .optional();
  }

  @Override
  public boolean hasAnswers(String questionId) {
    return jdbc.sql("select exists(select 1 from quiz_answers where question_id=:questionId)")
            .param("questionId", questionId)
            .query(Integer.class)
            .single()
        == 1;
  }

  @Override
  public void saveQuestion(Question question, Instant now) {
    jdbc.sql(
            """
            insert into questions (
              id,media_item_id,question_type,content,explanation,enabled,sort_order,created_at,updated_at
            ) values (:id,:mediaId,:type,:content,:explanation,:enabled,:sortOrder,:now,:now)
            on conflict(id) do update set
              question_type=excluded.question_type,content=excluded.content,
              explanation=excluded.explanation,enabled=excluded.enabled,
              sort_order=excluded.sort_order,updated_at=excluded.updated_at
            """)
        .param("id", question.id())
        .param("mediaId", question.mediaItemId())
        .param("type", question.questionType())
        .param("content", question.content().trim())
        .param("explanation", question.explanation() == null ? "" : question.explanation().trim())
        .param("enabled", question.enabled() ? 1 : 0)
        .param("sortOrder", question.sortOrder())
        .param("now", now.toEpochMilli())
        .update();
    List<String> retainedOptionIds = question.options().stream().map(Question.Option::id).toList();
    jdbc.sql(
            "delete from question_options where question_id=:questionId and id not in (:retainedIds)")
        .param("questionId", question.id())
        .param("retainedIds", retainedOptionIds)
        .update();
    for (Question.Option option : question.options()) {
      jdbc.sql(
              """
              insert into question_options (id,question_id,content,correct,sort_order)
              values (:id,:questionId,:content,:correct,:sortOrder)
              on conflict(id) do update set content=excluded.content,
                correct=excluded.correct,sort_order=excluded.sort_order
              """)
          .param("id", option.id())
          .param("questionId", question.id())
          .param("content", option.content().trim())
          .param("correct", option.correct() ? 1 : 0)
          .param("sortOrder", option.sortOrder())
          .update();
    }
  }

  @Override
  public void insertAttempt(Attempt attempt, List<Answer> answers, Instant now) {
    jdbc.sql(
            """
            insert into quiz_attempts (
              id,user_id,media_item_id,score,correct_count,total_count,duration_ms,submitted_at,created_at
            ) values (:id,:userId,:mediaId,:score,:correctCount,:totalCount,:duration,:now,:now)
            """)
        .param("id", attempt.id())
        .param("userId", attempt.userId())
        .param("mediaId", attempt.mediaItemId())
        .param("score", attempt.score())
        .param("correctCount", attempt.correctCount())
        .param("totalCount", attempt.totalCount())
        .param("duration", attempt.durationMs())
        .param("now", now.toEpochMilli())
        .update();
    for (Answer answer : answers) {
      jdbc.sql(
              """
              insert into quiz_answers (
                id,attempt_id,question_id,selected_option_id,correct,duration_ms,created_at
              ) values (:id,:attemptId,:questionId,:optionId,:correct,:duration,:now)
              """)
          .param("id", answer.id())
          .param("attemptId", attempt.id())
          .param("questionId", answer.questionId())
          .param("optionId", answer.selectedOptionId())
          .param("correct", answer.correct() ? 1 : 0)
          .param("duration", answer.durationMs())
          .param("now", now.toEpochMilli())
          .update();
    }
  }

  @Override
  public List<Attempt> findAttempts(String userId, String mediaItemId) {
    return jdbc.sql(
            "select * from quiz_attempts where user_id=:userId and media_item_id=:mediaItemId "
                + "order by submitted_at desc,id desc")
        .param("userId", userId)
        .param("mediaItemId", mediaItemId)
        .query(
            (rs, row) ->
                new Attempt(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("media_item_id"),
                    rs.getInt("score"),
                    rs.getInt("correct_count"),
                    rs.getInt("total_count"),
                    rs.getLong("duration_ms"),
                    Instant.ofEpochMilli(rs.getLong("submitted_at"))))
        .list();
  }

  private Question mapQuestion(ResultSet rs, int row) throws SQLException {
    String id = rs.getString("id");
    List<Question.Option> options =
        jdbc.sql("select * from question_options where question_id=:id order by sort_order,id")
            .param("id", id)
            .query(
                (optionRs, optionRow) ->
                    new Question.Option(
                        optionRs.getString("id"),
                        optionRs.getString("content"),
                        optionRs.getInt("correct") == 1,
                        optionRs.getInt("sort_order")))
            .list();
    return new Question(
        id,
        rs.getString("media_item_id"),
        rs.getString("question_type"),
        rs.getString("content"),
        rs.getString("explanation"),
        rs.getInt("enabled") == 1,
        rs.getInt("sort_order"),
        options);
  }
}
