package com.shangan.learning.infrastructure;

import java.time.Instant;
import java.util.Optional;

/** 用户与视频维度的累计可信进度持久化边界。 */
public interface VideoProgressRepository {
  Optional<Progress> find(String userId, String mediaItemId);

  Progress synchronize(
      String id,
      String userId,
      String mediaItemId,
      long absoluteMaximumMs,
      long verifiedWatchDeltaMs,
      boolean completed,
      Instant now);

  record Progress(
      String id,
      String userId,
      String mediaItemId,
      long maxVerifiedPositionMs,
      long verifiedWatchMs,
      Instant completedAt) {}
}
