package com.shangan.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.learning.application.AliveCheckScheduler;
import org.junit.jupiter.api.Test;

/** 验证监督等级对应的加密随机验活区间和关闭语义。 */
class AliveCheckSchedulerTest {
  private final AliveCheckScheduler scheduler = new AliveCheckScheduler();

  @Test
  void schedulesDueTrustedWatchTimeInsideConfiguredRanges() {
    assertInside("NORMAL", 40, 60);
    assertInside("STRICT", 20, 40);
    assertInside("INTENSE", 10, 25);
  }

  @Test
  void offLevelDoesNotScheduleAliveCheck() {
    assertThat(scheduler.nextDueWatchMs("OFF", 123_000)).isEmpty();
  }

  private void assertInside(String level, long minimumMinutes, long maximumMinutes) {
    long baseline = 123_000;
    long due = scheduler.nextDueWatchMs(level, baseline).orElseThrow();
    assertThat(due)
        .isBetween(baseline + minimumMinutes * 60_000, baseline + maximumMinutes * 60_000);
  }
}
