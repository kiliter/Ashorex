package com.shangan.identity.infrastructure;

import com.shangan.identity.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用 JdbcClient 持久化用户与 Refresh Token。 */
@Repository
public class JdbcUserRepository implements UserRepository {

  private static final String USER_COLUMNS =
      """
            id, username, password_hash, display_name, role, timezone,
            alive_check_level, alive_check_interval_percent, day_end_local_time, enabled
            """;

  private final JdbcClient jdbc;

  public JdbcUserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<User> findByUsername(String username) {
    return jdbc.sql("select " + USER_COLUMNS + " from users where username = :username")
        .param("username", username)
        .query(this::mapUser)
        .optional();
  }

  @Override
  public Optional<User> findById(String id) {
    return jdbc.sql("select " + USER_COLUMNS + " from users where id = :id")
        .param("id", id)
        .query(this::mapUser)
        .optional();
  }

  @Override
  public List<User> findAll() {
    return jdbc.sql("select " + USER_COLUMNS + " from users order by username")
        .query(this::mapUser)
        .list();
  }

  @Override
  public boolean hasAdministrator() {
    return jdbc.sql("select count(*) from users where role = 'ADMIN'").query(Integer.class).single()
        > 0;
  }

  @Override
  public void insert(User user, Instant createdAt) {
    jdbc.sql(
            """
                        insert into users (
                            id, username, password_hash, display_name, role, timezone,
                            alive_check_level, alive_check_interval_percent,
                            day_end_local_time, enabled, created_at, updated_at
                        ) values (
                            :id, :username, :passwordHash, :displayName, :role, :timezone,
                            :aliveCheckLevel, :aliveCheckIntervalPercent,
                            :dayEndLocalTime, :enabled, :createdAt, :updatedAt
                        )
                        """)
        .param("id", user.id())
        .param("username", user.username())
        .param("passwordHash", user.passwordHash())
        .param("displayName", user.displayName())
        .param("role", user.role())
        .param("timezone", user.timezone())
        .param("aliveCheckLevel", user.aliveCheckLevel())
        .param("aliveCheckIntervalPercent", user.aliveCheckIntervalPercent())
        .param("dayEndLocalTime", user.dayEndLocalTime())
        .param("enabled", user.enabled() ? 1 : 0)
        .param("createdAt", createdAt.toEpochMilli())
        .param("updatedAt", createdAt.toEpochMilli())
        .update();
  }

  @Override
  public void updatePreferences(
      String userId,
      String timezone,
      String aliveCheckLevel,
      int aliveCheckIntervalPercent,
      String dayEndLocalTime,
      Instant now) {
    jdbc.sql(
            """
                        update users
                        set timezone = :timezone,
                            alive_check_level = :aliveCheckLevel,
                            alive_check_interval_percent = :aliveCheckIntervalPercent,
                            day_end_local_time = :dayEndLocalTime,
                            updated_at = :updatedAt
                        where id = :userId
                        """)
        .param("timezone", timezone)
        .param("aliveCheckLevel", aliveCheckLevel)
        .param("aliveCheckIntervalPercent", aliveCheckIntervalPercent)
        .param("dayEndLocalTime", dayEndLocalTime)
        .param("updatedAt", now.toEpochMilli())
        .param("userId", userId)
        .update();
  }

  @Override
  public void setEnabled(String userId, boolean enabled, Instant now) {
    jdbc.sql("update users set enabled = :enabled, updated_at = :now where id = :id")
        .params(Map.of("enabled", enabled ? 1 : 0, "now", now.toEpochMilli(), "id", userId))
        .update();
  }

  @Override
  public void revokeRefreshTokensByUserId(String userId, Instant revokedAt) {
    jdbc.sql(
            "update refresh_tokens set revoked_at = :now "
                + "where user_id = :userId and revoked_at is null")
        .params(Map.of("now", revokedAt.toEpochMilli(), "userId", userId))
        .update();
  }

  @Override
  public void insertRefreshToken(
      String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt) {
    jdbc.sql(
            """
                        insert into refresh_tokens (
                            id, user_id, token_hash, expires_at, revoked_at, created_at
                        ) values (
                            :id, :userId, :tokenHash, :expiresAt, null, :createdAt
                        )
                        """)
        .params(
            Map.of(
                "id", id,
                "userId", userId,
                "tokenHash", tokenHash,
                "expiresAt", expiresAt.toEpochMilli(),
                "createdAt", createdAt.toEpochMilli()))
        .update();
  }

  @Override
  public Optional<RefreshTokenRecord> findRefreshTokenByHash(String tokenHash) {
    return jdbc.sql(
            """
                        select id, user_id, expires_at, revoked_at
                        from refresh_tokens
                        where token_hash = :tokenHash
                        """)
        .param("tokenHash", tokenHash)
        .query(
            (resultSet, rowNumber) ->
                new RefreshTokenRecord(
                    resultSet.getString("id"),
                    resultSet.getString("user_id"),
                    Instant.ofEpochMilli(resultSet.getLong("expires_at")),
                    resultSet.getObject("revoked_at") == null
                        ? null
                        : Instant.ofEpochMilli(resultSet.getLong("revoked_at"))))
        .optional();
  }

  @Override
  public void revokeRefreshToken(String id, Instant revokedAt) {
    jdbc.sql(
            """
                        update refresh_tokens
                        set revoked_at = :revokedAt
                        where id = :id and revoked_at is null
                        """)
        .params(Map.of("revokedAt", revokedAt.toEpochMilli(), "id", id))
        .update();
  }

  private User mapUser(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
    return new User(
        resultSet.getString("id"),
        resultSet.getString("username"),
        resultSet.getString("password_hash"),
        resultSet.getString("display_name"),
        resultSet.getString("role"),
        resultSet.getString("timezone"),
        resultSet.getString("alive_check_level"),
        resultSet.getInt("alive_check_interval_percent"),
        resultSet.getString("day_end_local_time"),
        resultSet.getInt("enabled") == 1);
  }
}
