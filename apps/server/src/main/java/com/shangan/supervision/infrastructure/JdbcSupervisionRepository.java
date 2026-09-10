package com.shangan.supervision.infrastructure;

import com.shangan.supervision.domain.Supervision;
import com.shangan.supervision.domain.SupervisionKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 督学绑定的 SQLite 持久化实现。 */
@Repository
public class JdbcSupervisionRepository implements SupervisionRepository {

  private static final String SELECT =
      """
      SELECT id, learner_user_id, supervisor_user_id, kind,
             can_view, can_nag, can_edit_goal, can_add_todo, archived_at
        FROM supervisions
      """;

  private final JdbcClient jdbcClient;

  public JdbcSupervisionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<Supervision> find(String learnerUserId, String supervisorUserId) {
    return jdbcClient
        .sql(SELECT + " WHERE learner_user_id = :learner AND supervisor_user_id = :supervisor")
        .param("learner", learnerUserId)
        .param("supervisor", supervisorUserId)
        .query(this::map)
        .optional();
  }

  @Override
  public Optional<Supervision> findById(String id) {
    return jdbcClient.sql(SELECT + " WHERE id = :id").param("id", id).query(this::map).optional();
  }

  @Override
  public Optional<Supervision> findActivePrimaryByLearner(String learnerUserId) {
    return jdbcClient
        .sql(
            SELECT
                + " WHERE learner_user_id = :learner AND kind = 'PRIMARY' AND archived_at IS NULL")
        .param("learner", learnerUserId)
        .query(this::map)
        .optional();
  }

  @Override
  public List<Supervision> findActiveBySupervisor(String supervisorUserId) {
    return jdbcClient
        .sql(
            SELECT
                + " WHERE supervisor_user_id = :supervisor AND archived_at IS NULL ORDER BY kind")
        .param("supervisor", supervisorUserId)
        .query(this::map)
        .list();
  }

  @Override
  public List<Supervision> findActiveByLearner(String learnerUserId) {
    return jdbcClient
        .sql(SELECT + " WHERE learner_user_id = :learner AND archived_at IS NULL ORDER BY kind")
        .param("learner", learnerUserId)
        .query(this::map)
        .list();
  }

  @Override
  public List<Supervision> findAll() {
    return jdbcClient.sql(SELECT + " ORDER BY learner_user_id, kind").query(this::map).list();
  }

  @Override
  public void insert(Supervision supervision, Instant createdAt) {
    long now = createdAt.toEpochMilli();
    jdbcClient
        .sql(
            """
            INSERT INTO supervisions (
                id, learner_user_id, supervisor_user_id, kind,
                can_view, can_nag, can_edit_goal, can_add_todo,
                archived_at, created_at, updated_at
            ) VALUES (
                :id, :learner, :supervisor, :kind,
                :canView, :canNag, :canEditGoal, :canAddTodo,
                NULL, :now, :now
            )
            """)
        .param("id", supervision.id())
        .param("learner", supervision.learnerUserId())
        .param("supervisor", supervision.supervisorUserId())
        .param("kind", supervision.kind().name())
        .param("canView", supervision.canView() ? 1 : 0)
        .param("canNag", supervision.canNag() ? 1 : 0)
        .param("canEditGoal", supervision.canEditGoal() ? 1 : 0)
        .param("canAddTodo", supervision.canAddTodo() ? 1 : 0)
        .param("now", now)
        .update();
  }

  @Override
  public void reactivate(
      String id,
      SupervisionKind kind,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo,
      Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE supervisions
               SET kind = :kind,
                   can_view = :canView,
                   can_nag = :canNag,
                   can_edit_goal = :canEditGoal,
                   can_add_todo = :canAddTodo,
                   archived_at = NULL,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("kind", kind.name())
        .param("canView", canView ? 1 : 0)
        .param("canNag", canNag ? 1 : 0)
        .param("canEditGoal", canEditGoal ? 1 : 0)
        .param("canAddTodo", canAddTodo ? 1 : 0)
        .param("now", now.toEpochMilli())
        .param("id", id)
        .update();
  }

  @Override
  public void updatePermissions(
      String id,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo,
      Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE supervisions
               SET can_view = :canView,
                   can_nag = :canNag,
                   can_edit_goal = :canEditGoal,
                   can_add_todo = :canAddTodo,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("canView", canView ? 1 : 0)
        .param("canNag", canNag ? 1 : 0)
        .param("canEditGoal", canEditGoal ? 1 : 0)
        .param("canAddTodo", canAddTodo ? 1 : 0)
        .param("now", now.toEpochMilli())
        .param("id", id)
        .update();
  }

  @Override
  public void archive(String id, Instant archivedAt) {
    jdbcClient
        .sql("UPDATE supervisions SET archived_at = :at, updated_at = :at WHERE id = :id")
        .param("at", archivedAt.toEpochMilli())
        .param("id", id)
        .update();
  }

  @Override
  public void restore(String id, Instant now) {
    jdbcClient
        .sql("UPDATE supervisions SET archived_at = NULL, updated_at = :now WHERE id = :id")
        .param("now", now.toEpochMilli())
        .param("id", id)
        .update();
  }

  @Override
  public void delete(String id) {
    jdbcClient.sql("DELETE FROM supervisions WHERE id = :id").param("id", id).update();
  }

  private Supervision map(ResultSet row, int rowNumber) throws SQLException {
    long archivedAt = row.getLong("archived_at");
    boolean archivedIsNull = row.wasNull() || archivedAt == 0;
    return new Supervision(
        row.getString("id"),
        row.getString("learner_user_id"),
        row.getString("supervisor_user_id"),
        SupervisionKind.valueOf(row.getString("kind")),
        row.getInt("can_view") == 1,
        row.getInt("can_nag") == 1,
        row.getInt("can_edit_goal") == 1,
        row.getInt("can_add_todo") == 1,
        archivedIsNull ? null : Instant.ofEpochMilli(archivedAt));
  }
}
