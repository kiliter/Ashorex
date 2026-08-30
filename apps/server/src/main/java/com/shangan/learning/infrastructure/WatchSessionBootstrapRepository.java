package com.shangan.learning.infrastructure;

import java.time.Instant;
import java.util.Optional;

/** Task 8 创建播放会话所需的最小持久化边界。 */
public interface WatchSessionBootstrapRepository {
  void insert(SessionPlayback session, Instant now);

  Optional<SessionPlayback> find(String sessionId);

  record SessionPlayback(
      String id,
      String userId,
      String mediaItemId,
      String embyItemId,
      String planItemId,
      String deviceId,
      String playSessionId,
      String upstreamPath,
      boolean hls,
      long durationMs) {}
}
