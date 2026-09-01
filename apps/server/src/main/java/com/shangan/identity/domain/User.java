package com.shangan.identity.domain;

/** 用户聚合的身份与偏好快照。 */
public record User(
    String id,
    String username,
    String passwordHash,
    String displayName,
    String role,
    String timezone,
    String aliveCheckLevel,
    int aliveCheckIntervalPercent,
    String dayEndLocalTime,
    boolean enabled) {}
