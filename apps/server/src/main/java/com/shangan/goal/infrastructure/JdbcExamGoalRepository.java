package com.shangan.goal.infrastructure;

import com.shangan.goal.domain.ExamGoal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 考试目标的 SQLite 持久化实现。 */
@Repository
public class JdbcExamGoalRepository implements ExamGoalRepository {

  private static final String SELECT =
      "SELECT id, user_id, name, exam_date, note, is_primary FROM exam_goals";

  private final JdbcClient jdbcClient;

  public JdbcExamGoalRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public List<ExamGoal> findByUser(String userId) {
    return jdbcClient
        .sql(SELECT + " WHERE user_id = :userId ORDER BY is_primary DESC, exam_date, name")
        .param("userId", userId)
        .query(this::map)
        .list();
  }

  @Override
  public Optional<ExamGoal> findById(String id) {
    return jdbcClient.sql(SELECT + " WHERE id = :id").param("id", id).query(this::map).optional();
  }

  @Override
  public void insert(ExamGoal goal, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO exam_goals (
                id, user_id, name, exam_date, note, is_primary, created_at, updated_at
            ) VALUES (
                :id, :userId, :name, :examDate, :note, :isPrimary, :now, :now
            )
            """)
        .param("id", goal.id())
        .param("userId", goal.userId())
        .param("name", goal.name())
        .param("examDate", goal.examDate().toString())
        .param("note", goal.note())
        .param("isPrimary", goal.primary() ? 1 : 0)
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void update(ExamGoal goal, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE exam_goals
               SET name = :name,
                   exam_date = :examDate,
                   note = :note,
                   is_primary = :isPrimary,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("name", goal.name())
        .param("examDate", goal.examDate().toString())
        .param("note", goal.note())
        .param("isPrimary", goal.primary() ? 1 : 0)
        .param("now", now.toEpochMilli())
        .param("id", goal.id())
        .update();
  }

  @Override
  public void delete(String id) {
    jdbcClient.sql("DELETE FROM exam_goals WHERE id = :id").param("id", id).update();
  }

  @Override
  public void clearPrimary(String userId, String exceptGoalId, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE exam_goals
               SET is_primary = 0, updated_at = :now
             WHERE user_id = :userId AND id <> :exceptId AND is_primary = 1
            """)
        .param("now", now.toEpochMilli())
        .param("userId", userId)
        .param("exceptId", exceptGoalId)
        .update();
  }

  private ExamGoal map(ResultSet row, int rowNumber) throws SQLException {
    return new ExamGoal(
        row.getString("id"),
        row.getString("user_id"),
        row.getString("name"),
        LocalDate.parse(row.getString("exam_date")),
        row.getString("note"),
        row.getInt("is_primary") == 1);
  }
}
