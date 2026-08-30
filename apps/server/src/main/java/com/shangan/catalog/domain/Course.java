package com.shangan.catalog.domain;

import java.time.Instant;

/** 管理员绑定的课程及其最近一次同步结果。 */
public record Course(
    String id,
    String name,
    String description,
    String embyParentItemId,
    boolean enabled,
    int sortOrder,
    Instant lastSyncedAt,
    String lastSyncError) {}
