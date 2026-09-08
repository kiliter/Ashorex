package com.shangan.identity.domain;

/** 用户账号状态；归档账号立即无法登录，也不参与催办扫描与统计聚合。 */
public enum UserStatus {
  ACTIVE,
  ARCHIVED
}
