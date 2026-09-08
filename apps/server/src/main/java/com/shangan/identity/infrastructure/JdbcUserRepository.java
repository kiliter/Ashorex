package com.shangan.identity.infrastructure;

import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 用户与 Refresh Token 的 SQLite 持久化实现；角色单独存表并在读取时聚合。 */
@Repository
public class JdbcUserRepository implements UserRepository {

  private static final String SELECT_USER =
      """
      SELECT id, username, password_hash, display_name, timezone, status, archived_at
        FROM users
      """;

  private final JdbcClient jdbcClient;

  public JdbcUserRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<User> findByUsername(String username) {
    return jdbcClient
        .sql(SELECT_USER + " WHERE username = :username")
        .param("username", username)
        .query(this::mapUserWithoutRoles)
        .optional()
        .map(this::withRoles);
  }

  @Override
  public Optional<User> findById(String id) {
    return jdbcClient
        .sql(SELECT_USER + " WHERE id = :id")
        .param("id", id)
        .query(this::mapUserWithoutRoles)
        .optional()
        .map(this::withRoles);
  }

  @Override
  public List<User> findAll() {
    return withRoles(
        jdbcClient.sql(SELECT_USER + " ORDER BY username").query(this::mapUserWithoutRoles).list());
  }

  @Override
  public List<User> findActive() {
    return withRoles(
        jdbcClient
            .sql(SELECT_USER + " WHERE status = 'ACTIVE' ORDER BY username")
            .query(this::mapUserWithoutRoles)
            .list());
  }

  @Override
  public boolean hasAdministrator() {
    Long count =
        jdbcClient
            .sql("SELECT count(*) FROM user_roles WHERE role = 'ADMIN'")
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

  @Override
  public void insert(User user, Instant createdAt) {
    long now = createdAt.toEpochMilli();
    jdbcClient
        .sql(
            """
            INSERT INTO users (
                id, username, password_hash, display_name, timezone,
                status, archived_at, created_at, updated_at
            ) VALUES (
                :id, :username, :passwordHash, :displayName, :timezone,
                :status, :archivedAt, :now, :now
            )
            """)
        .param("id", user.id())
        .param("username", user.username())
        .param("passwordHash", user.passwordHash())
        .param("displayName", user.displayName())
        .param("timezone", user.timezone())
        .param("status", user.status().name())
        .param("archivedAt", user.archivedAt() == null ? null : user.archivedAt().toEpochMilli())
        .param("now", now)
        .update();
    for (UserRole role : user.roles()) {
      addRole(user.id(), role, createdAt);
    }
  }

  @Override
  public void updateDisplayName(String userId, String displayName, Instant now) {
    jdbcClient
        .sql("UPDATE users SET display_name = :displayName, updated_at = :now WHERE id = :id")
        .param("displayName", displayName)
        .param("now", now.toEpochMilli())
        .param("id", userId)
        .update();
  }

  @Override
  public void updateTimezone(String userId, String timezone, Instant now) {
    jdbcClient
        .sql("UPDATE users SET timezone = :timezone, updated_at = :now WHERE id = :id")
        .param("timezone", timezone)
        .param("now", now.toEpochMilli())
        .param("id", userId)
        .update();
  }

  @Override
  public void updatePasswordHash(String userId, String passwordHash, Instant now) {
    jdbcClient
        .sql("UPDATE users SET password_hash = :hash, updated_at = :now WHERE id = :id")
        .param("hash", passwordHash)
        .param("now", now.toEpochMilli())
        .param("id", userId)
        .update();
  }

  @Override
  public void updateStatus(String userId, boolean archived, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE users
               SET status = :status,
                   archived_at = :archivedAt,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("status", archived ? UserStatus.ARCHIVED.name() : UserStatus.ACTIVE.name())
        .param("archivedAt", archived ? now.toEpochMilli() : null)
        .param("now", now.toEpochMilli())
        .param("id", userId)
        .update();
  }

  @Override
  public void addRole(String userId, UserRole role, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO user_roles (user_id, role, created_at)
            VALUES (:userId, :role, :now)
            ON CONFLICT(user_id, role) DO NOTHING
            """)
        .param("userId", userId)
        .param("role", role.name())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void removeRole(String userId, UserRole role) {
    jdbcClient
        .sql("DELETE FROM user_roles WHERE user_id = :userId AND role = :role")
        .param("userId", userId)
        .param("role", role.name())
        .update();
  }

  @Override
  public void revokeRefreshTokensByUserId(String userId, Instant revokedAt) {
    jdbcClient
        .sql(
            """
            UPDATE refresh_tokens
               SET revoked_at = :revokedAt
             WHERE user_id = :userId AND revoked_at IS NULL
            """)
        .param("revokedAt", revokedAt.toEpochMilli())
        .param("userId", userId)
        .update();
  }

  @Override
  public void insertRefreshToken(
      String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt) {
    jdbcClient
        .sql(
            """
            INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at)
            VALUES (:id, :userId, :tokenHash, :expiresAt, :createdAt)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("tokenHash", tokenHash)
        .param("expiresAt", expiresAt.toEpochMilli())
        .param("createdAt", createdAt.toEpochMilli())
        .update();
  }

  @Override
  public Optional<RefreshTokenRecord> findRefreshTokenByHash(String tokenHash) {
    return jdbcClient
        .sql(
            """
            SELECT id, user_id, expires_at, revoked_at
              FROM refresh_tokens
             WHERE token_hash = :tokenHash
            """)
        .param("tokenHash", tokenHash)
        .query(
            (row, rowNumber) -> {
              long revokedAt = row.getLong("revoked_at");
              return new RefreshTokenRecord(
                  row.getString("id"),
                  row.getString("user_id"),
                  Instant.ofEpochMilli(row.getLong("expires_at")),
                  row.wasNull() || revokedAt == 0 ? null : Instant.ofEpochMilli(revokedAt));
            })
        .optional();
  }

  @Override
  public void revokeRefreshToken(String id, Instant revokedAt) {
    jdbcClient
        .sql("UPDATE refresh_tokens SET revoked_at = :revokedAt WHERE id = :id")
        .param("revokedAt", revokedAt.toEpochMilli())
        .param("id", id)
        .update();
  }

  /** 先读用户行再一次性补齐角色，避免逐用户查询造成 N+1。 */
  private User withRoles(User user) {
    return new User(
        user.id(),
        user.username(),
        user.passwordHash(),
        user.displayName(),
        user.timezone(),
        user.status(),
        user.archivedAt(),
        rolesOf(user.id()));
  }

  private List<User> withRoles(List<User> users) {
    if (users.isEmpty()) {
      return List.of();
    }
    Map<String, Set<UserRole>> roles = allRoles();
    List<User> result = new ArrayList<>(users.size());
    for (User user : users) {
      result.add(
          new User(
              user.id(),
              user.username(),
              user.passwordHash(),
              user.displayName(),
              user.timezone(),
              user.status(),
              user.archivedAt(),
              roles.getOrDefault(user.id(), Set.of())));
    }
    return List.copyOf(result);
  }

  private Set<UserRole> rolesOf(String userId) {
    List<String> values =
        jdbcClient
            .sql("SELECT role FROM user_roles WHERE user_id = :userId")
            .param("userId", userId)
            .query(String.class)
            .list();
    Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
    values.forEach(value -> roles.add(UserRole.valueOf(value)));
    return roles;
  }

  private Map<String, Set<UserRole>> allRoles() {
    Map<String, Set<UserRole>> result = new LinkedHashMap<>();
    jdbcClient
        .sql("SELECT user_id, role FROM user_roles")
        .query(
            (row, rowNumber) -> {
              result
                  .computeIfAbsent(row.getString("user_id"), key -> EnumSet.noneOf(UserRole.class))
                  .add(UserRole.valueOf(row.getString("role")));
              return null;
            })
        .list();
    return result;
  }

  private User mapUserWithoutRoles(java.sql.ResultSet row, int rowNumber)
      throws java.sql.SQLException {
    long archivedAt = row.getLong("archived_at");
    return new User(
        row.getString("id"),
        row.getString("username"),
        row.getString("password_hash"),
        row.getString("display_name"),
        row.getString("timezone"),
        UserStatus.valueOf(row.getString("status")),
        row.wasNull() || archivedAt == 0 ? null : Instant.ofEpochMilli(archivedAt),
        Set.of());
  }
}
