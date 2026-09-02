package com.shangan.catalog.domain;

/** 本地课程课时快照；enabled 与 sortOrder 只由管理员控制。 */
public record MediaItem(
    String id,
    String courseId,
    String embyItemId,
    String embyItemType,
    String sourceFingerprint,
    String title,
    long durationMs,
    boolean enabled,
    int sortOrder,
    boolean available) {

  /** 兼容未涉及 Emby 来源身份的既有业务测试和调用方。 */
  public MediaItem(
      String id,
      String courseId,
      String embyItemId,
      String title,
      long durationMs,
      boolean enabled,
      int sortOrder,
      boolean available) {
    this(id, courseId, embyItemId, "Video", null, title, durationMs, enabled, sortOrder, available);
  }
}
