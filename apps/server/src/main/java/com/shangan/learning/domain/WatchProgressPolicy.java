package com.shangan.learning.domain;

import java.time.Duration;
import java.time.Instant;

/** 纯领域策略：按心跳时间、位置、前台状态和序号计算可信观看增量。 */
public final class WatchProgressPolicy {
  private static final long MAX_ELAPSED_MS = 15_000;
  private static final long FORWARD_GRACE_MS = 3_000;

  /** 计算一次心跳结果；调用方负责持久化返回的聚合值。 */
  public Decision evaluate(State state, Heartbeat heartbeat, Instant receivedAt) {
    if (heartbeat.sequence() <= state.lastSequence()) {
      return new Decision(
          state.lastReportedPositionMs(),
          state.maxVerifiedPositionMs(),
          state.verifiedWatchMs(),
          state.lastSequence(),
          state.lastHeartbeatAt(),
          true,
          state.maxVerifiedPositionMs(),
          true);
    }

    long currentPosition = Math.max(0, heartbeat.positionMs());
    long elapsedMs =
        clamp(Duration.between(state.lastHeartbeatAt(), receivedAt).toMillis(), 0, MAX_ELAPSED_MS);
    boolean countable = heartbeat.playing() && heartbeat.foreground() && !state.aliveCheckPending();
    if (!countable) {
      boolean insideTrustedRegion = currentPosition <= state.maxVerifiedPositionMs();
      long nextReported = insideTrustedRegion ? currentPosition : state.maxVerifiedPositionMs();
      return new Decision(
          nextReported,
          state.maxVerifiedPositionMs(),
          state.verifiedWatchMs(),
          heartbeat.sequence(),
          receivedAt,
          insideTrustedRegion,
          state.maxVerifiedPositionMs(),
          false);
    }

    long positionDelta = currentPosition - state.lastReportedPositionMs();
    long allowedForwardDelta = Math.round(elapsedMs * 1.25d) + FORWARD_GRACE_MS;
    long acceptedForwardDelta = clamp(positionDelta, 0, allowedForwardDelta);
    long countedWatchMs = Math.min(elapsedMs, acceptedForwardDelta);
    boolean seekAllowed = positionDelta <= allowedForwardDelta;
    long acceptedPosition =
        positionDelta <= 0
            ? currentPosition
            : state.lastReportedPositionMs() + acceptedForwardDelta;
    long nextMaximum = Math.max(state.maxVerifiedPositionMs(), acceptedPosition);
    return new Decision(
        acceptedPosition,
        nextMaximum,
        state.verifiedWatchMs() + countedWatchMs,
        heartbeat.sequence(),
        receivedAt,
        seekAllowed,
        nextMaximum,
        false);
  }

  /** V1 视频完成阈值：时长减去 30 秒与时长 2% 中较小者。 */
  public boolean completed(long maxVerifiedPositionMs, long durationMs) {
    long tolerance = Math.min(30_000L, Math.round(durationMs * 0.02d));
    return maxVerifiedPositionMs >= Math.max(0, durationMs - tolerance);
  }

  private long clamp(long value, long minimum, long maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  /** 持久化的会话聚合状态。 */
  public record State(
      long lastReportedPositionMs,
      long maxVerifiedPositionMs,
      long verifiedWatchMs,
      long lastSequence,
      Instant lastHeartbeatAt,
      boolean aliveCheckPending) {}

  /** 客户端心跳中参与可信校验的字段。 */
  public record Heartbeat(long sequence, long positionMs, boolean playing, boolean foreground) {}

  /** 单次计算后的新聚合状态及客户端纠偏指令。 */
  public record Decision(
      long lastReportedPositionMs,
      long maxVerifiedPositionMs,
      long verifiedWatchMs,
      long lastSequence,
      Instant lastHeartbeatAt,
      boolean seekAllowed,
      long trustedPositionMs,
      boolean duplicate) {}
}
