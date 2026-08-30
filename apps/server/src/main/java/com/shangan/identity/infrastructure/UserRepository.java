package com.shangan.identity.infrastructure;

import com.shangan.identity.domain.User;
import java.time.Instant;
import java.util.Optional;

/** 身份模块的显式持久化边界。 */
public interface UserRepository {

  Optional<User> findByUsername(String username);

  Optional<User> findById(String id);

  boolean hasAdministrator();

  void insert(User user, Instant createdAt);

  void updatePreferences(
      String userId, String timezone, String aliveCheckLevel, String dayEndLocalTime, Instant now);

  void insertRefreshToken(
      String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt);

  Optional<RefreshTokenRecord> findRefreshTokenByHash(String tokenHash);

  void revokeRefreshToken(String id, Instant revokedAt);

  /** 数据库中的 Refresh Token 元数据，不包含明文 Token。 */
  record RefreshTokenRecord(String id, String userId, Instant expiresAt, Instant revokedAt) {}
}
