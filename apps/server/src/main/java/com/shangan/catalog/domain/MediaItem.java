package com.shangan.catalog.domain;

/** 本地课程课时快照；enabled 与 sortOrder 只由管理员控制。 */
public record MediaItem(
    String id,
    String courseId,
    String embyItemId,
    String title,
    long durationMs,
    boolean enabled,
    int sortOrder,
    boolean available) {}
