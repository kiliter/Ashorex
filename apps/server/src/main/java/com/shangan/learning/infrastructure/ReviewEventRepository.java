package com.shangan.learning.infrastructure;

import java.time.Instant;
import java.time.LocalDate;

/** 复习审计事件只记录“复习过哪个课时”，不保存复习进度或完成量。 */
public interface ReviewEventRepository {
  void insertIfAbsent(
      String id,
      String userId,
      String mediaItemId,
      String watchSessionId,
      LocalDate reviewedOn,
      Instant createdAt);
}
