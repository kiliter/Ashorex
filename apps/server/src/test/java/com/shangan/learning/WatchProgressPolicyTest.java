package com.shangan.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.learning.domain.WatchProgressPolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 使用精确毫秒断言可信观看公式、幂等序号和不可计时场景。 */
class WatchProgressPolicyTest {
  private static final Instant START = Instant.parse("2026-08-30T00:00:00Z");
  private final WatchProgressPolicy policy = new WatchProgressPolicy();

  @Test
  void acceptsNormalForwardPlaybackAndCountsExactTime() {
    var result =
        policy.evaluate(state(0, 0, 0, 0, false), heartbeat(1, 10_000, true, true), at(10));

    assertThat(result.maxVerifiedPositionMs()).isEqualTo(10_000);
    assertThat(result.verifiedWatchMs()).isEqualTo(10_000);
    assertThat(result.seekAllowed()).isTrue();
  }

  @Test
  void capsObviousJumpAndRequiresClientToSeekBack() {
    var result =
        policy.evaluate(state(0, 0, 0, 0, false), heartbeat(1, 120_000, true, true), at(10));

    assertThat(result.maxVerifiedPositionMs()).isEqualTo(15_500);
    assertThat(result.verifiedWatchMs()).isEqualTo(10_000);
    assertThat(result.trustedPositionMs()).isEqualTo(15_500);
    assertThat(result.seekAllowed()).isFalse();
  }

  @Test
  void ignoresDuplicateSequenceWithoutDoubleCounting() {
    var state = state(10_000, 10_000, 10_000, 1, false);
    var result = policy.evaluate(state, heartbeat(1, 20_000, true, true), at(20));

    assertThat(result.duplicate()).isTrue();
    assertThat(result.maxVerifiedPositionMs()).isEqualTo(10_000);
    assertThat(result.verifiedWatchMs()).isEqualTo(10_000);
  }

  @Test
  void pausedBackgroundAndPendingAliveCheckDoNotCount() {
    var paused =
        policy.evaluate(state(0, 0, 0, 0, false), heartbeat(1, 10_000, false, true), at(10));
    var background =
        policy.evaluate(state(0, 0, 0, 0, false), heartbeat(1, 10_000, true, false), at(10));
    var pending =
        policy.evaluate(state(0, 0, 0, 0, true), heartbeat(1, 10_000, true, true), at(10));

    assertThat(paused.verifiedWatchMs()).isZero();
    assertThat(background.verifiedWatchMs()).isZero();
    assertThat(pending.verifiedWatchMs()).isZero();
    assertThat(paused.maxVerifiedPositionMs()).isZero();
    assertThat(background.maxVerifiedPositionMs()).isZero();
    assertThat(pending.maxVerifiedPositionMs()).isZero();
  }

  @Test
  void replayInsideVerifiedRegionCountsTimeButDoesNotGrowMaximum() {
    var result =
        policy.evaluate(
            state(30_000, 60_000, 50_000, 3, false), heartbeat(4, 40_000, true, true), at(10));

    assertThat(result.maxVerifiedPositionMs()).isEqualTo(60_000);
    assertThat(result.verifiedWatchMs()).isEqualTo(60_000);
    assertThat(result.seekAllowed()).isTrue();
  }

  @Test
  void usesTwoPercentOrThirtySecondsAsCompletionTolerance() {
    assertThat(policy.completed(98_000, 100_000)).isTrue();
    assertThat(policy.completed(1_969_999, 2_000_000)).isFalse();
    assertThat(policy.completed(1_970_000, 2_000_000)).isTrue();
  }

  private WatchProgressPolicy.State state(
      long lastPosition,
      long maxVerifiedPosition,
      long verifiedWatch,
      long lastSequence,
      boolean aliveCheckPending) {
    return new WatchProgressPolicy.State(
        lastPosition, maxVerifiedPosition, verifiedWatch, lastSequence, START, aliveCheckPending);
  }

  private WatchProgressPolicy.Heartbeat heartbeat(
      long sequence, long positionMs, boolean playing, boolean foreground) {
    return new WatchProgressPolicy.Heartbeat(sequence, positionMs, playing, foreground);
  }

  private Instant at(long seconds) {
    return START.plusSeconds(seconds);
  }
}
