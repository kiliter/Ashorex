package com.shangan.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.learning.application.AliveCheckScheduler;
import org.junit.jupiter.api.Test;

/** 验证百分比滑杆对应的视频绝对检查点、默认值和关闭语义。 */
class AliveCheckSchedulerTest {
  private final AliveCheckScheduler scheduler = new AliveCheckScheduler();

  @Test
  void schedulesCheckPointByVideoProgressPercent() {
    assertThat(scheduler.nextDuePositionMs("NORMAL", 50, 0, 600_000).orElseThrow())
        .isEqualTo(300_000);
    assertThat(scheduler.nextDuePositionMs("NORMAL", 10, 120_000, 600_000).orElseThrow())
        .isEqualTo(180_000);
  }

  @Test
  void offLevelDoesNotScheduleAliveCheck() {
    assertThat(scheduler.nextDuePositionMs("OFF", 50, 123_000, 600_000)).isEmpty();
  }

  @Test
  void doesNotScheduleCheckpointInsideCompletionTolerance() {
    assertThat(scheduler.nextDuePositionMs("NORMAL", 10, 540_000, 600_000)).isEmpty();
  }

  @Test
  void rejectsPercentageOutsideSliderRange() {
    assertThatThrownBy(() -> scheduler.nextDuePositionMs("NORMAL", 0, 0, 600_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> scheduler.nextDuePositionMs("NORMAL", 51, 0, 600_000))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
