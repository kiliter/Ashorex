package com.shangan.learning.infrastructure;

import com.shangan.learning.domain.WatchProgressPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 可信观看会话及验活记录的持久化边界。 */
public interface WatchSessionRepository {
  Optional<Session> findOwned(String userId, String sessionId);

  List<Session> findOpenForPlan(String userId, String planId);

  boolean updateHeartbeat(
      String sessionId,
      WatchProgressPolicy.Decision decision,
      boolean aliveCheckPending,
      String status,
      Instant now);

  void markSynced(String sessionId, long syncedVerifiedWatchMs, Instant now);

  void stop(String sessionId, String status, Instant endedAt);

  void setAliveState(
      String sessionId, boolean pending, Long nextDueWatchMs, String status, Instant now);

  void insertAliveCheck(String id, String sessionId, Instant requiredAt);

  Optional<AliveCheck> findUnansweredAliveCheck(String sessionId);

  void answerAliveCheck(String id, String status, Instant respondedAt);

  record Session(
      String id,
      String userId,
      String mediaItemId,
      String planItemId,
      String status,
      long durationMs,
      long lastReportedPositionMs,
      long maxVerifiedPositionMs,
      long verifiedWatchMs,
      long syncedVerifiedWatchMs,
      long lastSequence,
      Instant lastHeartbeatAt,
      Long aliveCheckDueWatchMs,
      boolean aliveCheckPending) {
    public boolean open() {
      return status.equals("ACTIVE") || status.equals("PAUSED");
    }
  }

  record AliveCheck(String id, Instant requiredAt) {}
}
