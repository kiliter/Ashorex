package com.shangan.identity.infrastructure;

import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 身份模块的显式持久化边界。 */
public interface UserRepository {

  Optional<User> findByUsername(String username);

  Optional<User> findById(String id);

  /** 按用户名列出全部用户（含归档），返回值不包含明文密码。 */
  List<User> findAll();

  /** 列出状态为 ACTIVE 的用户，供催办扫描与统计使用。 */
  List<User> findActive();

  boolean hasAdministrator();

  void insert(User user, Instant createdAt);

  void updateDisplayName(String userId, String displayName, Instant now);

  void updateTimezone(String userId, String timezone, Instant now);

  void updatePasswordHash(String userId, String passwordHash, Instant now);

  /** 归档或恢复账号；归档时应用服务会同时撤销其 Refresh Token。 */
  void updateStatus(String userId, boolean archived, Instant now);

  void addRole(String userId, UserRole role, Instant now);

  void removeRole(String userId, UserRole role);

  /** 撤销指定用户的全部有效 Refresh Token。 */
  void revokeRefreshTokensByUserId(String userId, Instant revokedAt);

  void insertRefreshToken(
      String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt);

  Optional<RefreshTokenRecord> findRefreshTokenByHash(String tokenHash);

  void revokeRefreshToken(String id, Instant revokedAt);

  /** 数据库中的 Refresh Token 元数据，不包含明文 Token。 */
  record RefreshTokenRecord(String id, String userId, Instant expiresAt, Instant revokedAt) {}
}
