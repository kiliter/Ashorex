package com.shangan.presence.infrastructure;

import com.shangan.presence.domain.PresenceSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 在线状态的 SQLite 实现；两类更新都是单行 upsert，开销极小。 */
@Repository
public class JdbcUserPresenceRepository implements UserPresenceRepository {

  private static final String SELECT =
      """
      SELECT user_id, last_heartbeat_at, last_effective_action_at, app_state, client_version, current_page, activity_state, activity_todo_id
        FROM user_presence
      """;

  private final JdbcClient jdbcClient;

  public JdbcUserPresenceRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<PresenceSnapshot> find(String userId) {
    return jdbcClient
        .sql(SELECT + " WHERE user_id = :userId")
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  @Override
  public List<PresenceSnapshot> findAll() {
    return jdbcClient.sql(SELECT).query(this::map).list();
  }

  @Override
  public void recordHeartbeat(
      String userId,
      Instant at,
      String appState,
      String clientVersion,
      com.shangan.presence.domain.AppActivity activity) {
    jdbcClient
        .sql(
            """
            INSERT INTO user_presence (
                user_id, last_heartbeat_at, last_effective_action_at,
                app_state, client_version, updated_at, current_page, activity_state, activity_todo_id
            ) VALUES (:userId, :at, NULL, :appState, :clientVersion, :at, :page, :activity, :todoId)
            ON CONFLICT(user_id) DO UPDATE SET
                last_heartbeat_at = excluded.last_heartbeat_at,
                app_state = excluded.app_state,
                client_version = excluded.client_version,
                current_page = excluded.current_page,
                activity_state = excluded.activity_state,
                activity_todo_id = excluded.activity_todo_id,
                updated_at = excluded.updated_at
            """)
        .param("userId", userId)
        .param("at", at.toEpochMilli())
        .param("appState", appState)
        .param("clientVersion", clientVersion == null ? "" : clientVersion)
        .param("page", activity.page().name())
        .param("activity", activity.state().name())
        .param("todoId", activity.todoId())
        .update();
  }

  @Override
  public void recordEffectiveAction(String userId, Instant at) {
    jdbcClient
        .sql(
            """
            INSERT INTO user_presence (
                user_id, last_heartbeat_at, last_effective_action_at,
                app_state, client_version, updated_at
            ) VALUES (:userId, NULL, :at, 'FOREGROUND', '', :at)
            ON CONFLICT(user_id) DO UPDATE SET
                last_effective_action_at = excluded.last_effective_action_at,
                updated_at = excluded.updated_at
            """)
        .param("userId", userId)
        .param("at", at.toEpochMilli())
        .update();
  }

  private PresenceSnapshot map(ResultSet row, int rowNumber) throws SQLException {
    return new PresenceSnapshot(
        row.getString("user_id"),
        nullableInstant(row, "last_heartbeat_at"),
        nullableInstant(row, "last_effective_action_at"),
        row.getString("app_state"),
        row.getString("client_version"),
        com.shangan.presence.domain.AppActivity.parse(
            row.getString("current_page"),
            row.getString("activity_state"),
            row.getString("activity_todo_id")));
  }

  private Instant nullableInstant(ResultSet row, String column) throws SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : Instant.ofEpochMilli(value);
  }
}
