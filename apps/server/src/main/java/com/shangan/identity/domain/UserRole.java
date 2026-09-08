package com.shangan.identity.domain;

/**
 * 账号可同时具备的角色。
 *
 * <p>{@code LEARNER} 与 {@code SUPERVISOR} 可并存于同一账号；{@code ADMIN} 只用于管理后台 Session 登录，不签发 App Token。
 */
public enum UserRole {
  LEARNER,
  SUPERVISOR,
  ADMIN
}
