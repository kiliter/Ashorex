package com.shangan.identity.domain;

import java.time.Instant;
import java.util.Set;

/** 用户聚合快照；角色为集合，账号状态决定能否登录。 */
public record User(
    String id,
    String username,
    String passwordHash,
    String displayName,
    String timezone,
    UserStatus status,
    Instant archivedAt,
    Set<UserRole> roles) {

  public User {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  /** 归档账号不允许登录，也不参与催办扫描。 */
  public boolean active() {
    return status == UserStatus.ACTIVE;
  }

  public boolean hasRole(UserRole role) {
    return roles.contains(role);
  }
}
