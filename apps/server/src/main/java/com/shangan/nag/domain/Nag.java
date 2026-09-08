package com.shangan.nag.domain;

import java.time.Instant;
import java.time.LocalDate;

/** 催办聚合，记录触发条件快照与用户回应。 */
public record Nag(
    String id,
    String userId,
    LocalDate localDate,
    int thresholdLevel,
    NagTrigger trigger,
    String triggeredByUserId,
    long idleMinutes,
    int pendingCount,
    String message,
    boolean requireReason,
    NagStatus status,
    Instant deliveredAt,
    Instant respondedAt,
    String reasonTag,
    String reasonText,
    String supervisorUserIdSnapshot,
    Instant createdAt,
    String title) {
  /** 历史记录和自动催办不含自定义标题，继续采用各渠道原展示。 */
  public Nag(
      String id,
      String userId,
      LocalDate localDate,
      int thresholdLevel,
      NagTrigger trigger,
      String triggeredByUserId,
      long idleMinutes,
      int pendingCount,
      String message,
      boolean requireReason,
      NagStatus status,
      Instant deliveredAt,
      Instant respondedAt,
      String reasonTag,
      String reasonText,
      String supervisorUserIdSnapshot,
      Instant createdAt) {
    this(
        id,
        userId,
        localDate,
        thresholdLevel,
        trigger,
        triggeredByUserId,
        idleMinutes,
        pendingCount,
        message,
        requireReason,
        status,
        deliveredAt,
        respondedAt,
        reasonTag,
        reasonText,
        supervisorUserIdSnapshot,
        createdAt,
        null);
  }

  /** 手动输入先裁剪并限制长度；不对自动生成的文案施加新限制。 */
  public static String manualText(String value, int limit) {
    if (value == null || value.isBlank()) return null;
    String text = value.trim();
    if (text.codePointCount(0, text.length()) > limit) {
      throw new com.shangan.common.api.BusinessException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "NAG_CONTENT_TOO_LONG",
          "催办标题最多 80 字，附加说明最多 1000 字");
    }
    return text;
  }

  /** 为已经校验的手动催办补充标题，不改变自动投递的生成路径。 */
  public Nag withTitle(String title) {
    return new Nag(
        id,
        userId,
        localDate,
        thresholdLevel,
        trigger,
        triggeredByUserId,
        idleMinutes,
        pendingCount,
        message,
        requireReason,
        status,
        deliveredAt,
        respondedAt,
        reasonTag,
        reasonText,
        supervisorUserIdSnapshot,
        createdAt,
        title);
  }

  public boolean awaitingResponse() {
    return status == NagStatus.PENDING || status == NagStatus.DELIVERED;
  }
}
