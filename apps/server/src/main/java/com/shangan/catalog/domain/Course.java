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
    String lastSyncError,
    Instant removedAt) {

  /** 兼容现有课程创建调用；新建课程默认不处于已移除状态。 */
  public Course(
      String id,
      String name,
      String description,
      String embyParentItemId,
      boolean enabled,
      int sortOrder,
      Instant lastSyncedAt,
      String lastSyncError) {
    this(
        id,
        name,
        description,
        embyParentItemId,
        enabled,
        sortOrder,
        lastSyncedAt,
        lastSyncError,
        null);
  }

  /** 已移除课程不再进入后台归档列表，但其课程身份和学习历史继续保留。 */
  public boolean removed() {
    return removedAt != null;
  }
}
