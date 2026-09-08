package com.shangan.presence.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 某用户的在线快照。
 *
 * <p>在线判定用最近心跳；空闲时长用「最近一次有效操作」，**心跳不算有效操作**，否则挂着 App 就永远不会被判定为空闲（见 ADR-0026）。
 */
public record PresenceSnapshot(
    String userId,
    Instant lastHeartbeatAt,
    Instant lastEffectiveActionAt,
    String appState,
    String clientVersion,
    AppActivity activity) {
  /** 兼容不含活动快照的历史调用；未知不被误报为正在学习。 */
  public PresenceSnapshot(
      String userId,
      Instant lastHeartbeatAt,
      Instant lastEffectiveActionAt,
      String appState,
      String clientVersion) {
    this(
        userId,
        lastHeartbeatAt,
        lastEffectiveActionAt,
        appState,
        clientVersion,
        AppActivity.unknown());
  }

  /** 按在线宽限期判定当前状态；空闲阈值由催办策略决定，这里只区分在线与离线。 */
  public PresenceState state(Instant now, Duration grace, Duration idleThreshold) {
    if (lastHeartbeatAt == null || Duration.between(lastHeartbeatAt, now).compareTo(grace) > 0) {
      return PresenceState.OFFLINE;
    }
    if (idleMinutes(now) >= idleThreshold.toMinutes()) {
      return PresenceState.IDLE;
    }
    return PresenceState.ONLINE;
  }

  public boolean online(Instant now, Duration grace) {
    return lastHeartbeatAt != null && Duration.between(lastHeartbeatAt, now).compareTo(grace) <= 0;
  }

  /** 距最近一次有效操作的分钟数；从未有过有效操作时按很大值处理，便于首次催办生效。 */
  public long idleMinutes(Instant now) {
    if (lastEffectiveActionAt == null) {
      return Long.MAX_VALUE / 2;
    }
    return Duration.between(lastEffectiveActionAt, now).toMinutes();
  }

  public static PresenceSnapshot empty(String userId) {
    return new PresenceSnapshot(userId, null, null, "BACKGROUND", "");
  }
}
