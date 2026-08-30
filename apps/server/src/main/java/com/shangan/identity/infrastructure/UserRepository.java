package com.shangan.identity.infrastructure;

import com.shangan.identity.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 身份模块的显式持久化边界。 */
public interface UserRepository {

  Optional<User> findByUsername(String username);

  Optional<User> findById(String id);

  /** 按用户名列出后台可管理用户，返回值不包含明文密码。 */
  List<User> findAll();

  boolean hasAdministrator();

  void insert(User user, Instant createdAt);

  void updatePreferences(
      String userId, String timezone, String aliveCheckLevel, String dayEndLocalTime, Instant now);

  /** 启用或禁用用户；禁用时应用服务会同时撤销其 Refresh Token。 */
  void setEnabled(String userId, boolean enabled, Instant now);

  /** 撤销指定用户的全部有效 Refresh Token。 */
  void revokeRefreshTokensByUserId(String userId, Instant revokedAt);

  void insertRefreshToken(
      String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt);

  Optional<RefreshTokenRecord> findRefreshTokenByHash(String tokenHash);

  void revokeRefreshToken(String id, Instant revokedAt);

  /** 数据库中的 Refresh Token 元数据，不包含明文 Token。 */
  record RefreshTokenRecord(String id, String userId, Instant expiresAt, Instant revokedAt) {}
}
