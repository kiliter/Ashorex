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
    Instant createdAt) {

  public boolean awaitingResponse() {
    return status == NagStatus.PENDING || status == NagStatus.DELIVERED;
  }
}
