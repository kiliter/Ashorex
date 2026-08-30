package com.shangan.learning.infrastructure;

import java.time.Instant;
import java.util.Optional;

/** Task 8 创建播放会话所需的最小持久化边界。 */
public interface WatchSessionBootstrapRepository {
  void insert(SessionPlayback session, Instant now);

  /** 创建新会话前结束同一用户仍开放的旧会话，保证 V1 单用户仅一个观看会话。 */
  void stopOpenForUser(String userId, Instant now);

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
      long durationMs,
      long startedPositionMs,
      Long aliveCheckDueWatchMs) {}
}
