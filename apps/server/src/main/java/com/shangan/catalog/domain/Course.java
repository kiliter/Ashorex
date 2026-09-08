package com.shangan.catalog.domain;

import java.time.Instant;

/**
 * 课程聚合。
 *
 * <p>标题、简介、年份与三张元数据投影都来自 Emby，本地只可编辑 {@code sortOrder} 与状态（见 ADR-0027）。 {@code sourceMissing} 与
 * {@code status} 正交：前者表示远端父节点不可达，后者表示管理员归档。
 */
public record Course(
    String id,
    String externalSource,
    String externalRef,
    String title,
    String overview,
    Integer productionYear,
    int sortOrder,
    CatalogStatus status,
    boolean sourceMissing,
    Instant lastSyncedAt,
    String lastSyncError,
    Instant archivedAt) {

  /** 只有既未归档也未失联的课程才对学习端可见。 */
  public boolean visibleToLearners() {
    return status == CatalogStatus.ACTIVE && !sourceMissing;
  }
}
