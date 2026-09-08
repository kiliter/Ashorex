package com.shangan.catalog.domain;

import java.time.Instant;

/**
 * 学习资源聚合（课时或材料）。
 *
 * <p>{@code id} 是不可变业务身份，永不重建；{@code externalRef} 是可安全替换的当前来源标识。 因此 Emby 侧换 ItemId
 * 或重新绑定父节点都不会影响进度、Todo 与统计（见 Spec 12.4）。
 */
public record LearningResource(
    String id,
    String courseId,
    ResourceType resourceType,
    String title,
    int sortIndex,
    Long durationMs,
    Integer pageCount,
    String externalRef,
    String sourceFingerprint,
    boolean available,
    CatalogStatus status,
    Instant archivedAt) {

  public boolean visibleToLearners() {
    return status == CatalogStatus.ACTIVE && available;
  }

  /** 资源的总量单位：视频用毫秒，材料用页数。缺失时无法计算目标进度。 */
  public boolean measurable() {
    return switch (resourceType) {
      case VIDEO -> durationMs != null && durationMs > 0;
      case DOCUMENT -> pageCount != null && pageCount > 0;
    };
  }

  /** 按资源类型换算当前进度千分比；总量缺失时返回 0。 */
  public int progressPermille(long positionMs, int positionPage) {
    if (!measurable()) {
      return 0;
    }
    return switch (resourceType) {
      case VIDEO -> (int) Math.min(1000, positionMs * 1000 / durationMs);
      case DOCUMENT -> Math.min(1000, positionPage * 1000 / pageCount);
    };
  }
}
