package com.shangan.nag.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 催办阈值分档、免打扰时段与文案渲染的纯规则测试。 */
class NagScanPolicyTest {

  private static EffectiveNagPolicy policy(
      int firstThresholdMinutes,
      int repeatIntervalMinutes,
      LocalTime quietStart,
      LocalTime quietEnd) {
    return new EffectiveNagPolicy(
        5,
        150,
        60,
        firstThresholdMinutes,
        repeatIntervalMinutes,
        3,
        10,
        quietStart,
        quietEnd,
        1,
        5,
        true,
        true,
        "{{user}} 今天还有 {{pending}} 项未完成，已经 {{idleMinutes}} 分钟没有任何操作。",
        true,
        true,
        true);
  }

  @Test
  @DisplayName("空闲未达首次阈值不产生档位，达到阈值即为第 1 档")
  void 首次阈值边界() {
    EffectiveNagPolicy policy = policy(90, 60, LocalTime.of(23, 30), LocalTime.of(7, 0));

    assertThat(policy.thresholdLevel(89)).isEmpty();
    assertThat(policy.thresholdLevel(90)).contains(1);
  }

  @Test
  @DisplayName("超过首次阈值后按重复间隔递增档位")
  void 分档按重复间隔递增() {
    EffectiveNagPolicy policy = policy(90, 60, LocalTime.of(23, 30), LocalTime.of(7, 0));

    assertThat(policy.thresholdLevel(149)).contains(1);
    assertThat(policy.thresholdLevel(150)).contains(2);
    assertThat(policy.thresholdLevel(209)).contains(2);
    assertThat(policy.thresholdLevel(210)).contains(3);
  }

  @Test
  @DisplayName("重复间隔为 0 时档位恒为 1，等价于当天只催一次")
  void 重复间隔为零时恒为第一档() {
    EffectiveNagPolicy policy = policy(90, 0, LocalTime.of(23, 30), LocalTime.of(7, 0));

    assertThat(policy.thresholdLevel(90)).contains(1);
    assertThat(policy.thresholdLevel(600)).contains(1);
    assertThat(policy.thresholdLevel(89)).isEmpty();
  }

  @Test
  @DisplayName("跨零点免打扰：23:30 起与 07:00 前都算免打扰，其间时刻不算")
  void 跨零点免打扰时段() {
    EffectiveNagPolicy policy = policy(90, 60, LocalTime.of(23, 30), LocalTime.of(7, 0));

    assertThat(policy.inQuietHours(LocalTime.of(23, 30))).isTrue();
    assertThat(policy.inQuietHours(LocalTime.of(2, 0))).isTrue();
    assertThat(policy.inQuietHours(LocalTime.of(6, 59))).isTrue();
    assertThat(policy.inQuietHours(LocalTime.of(7, 0))).isFalse();
    assertThat(policy.inQuietHours(LocalTime.of(23, 29))).isFalse();
  }

  @Test
  @DisplayName("同日内免打扰：区间起点含、终点不含")
  void 同日免打扰时段() {
    EffectiveNagPolicy policy = policy(90, 60, LocalTime.of(12, 0), LocalTime.of(14, 0));

    assertThat(policy.inQuietHours(LocalTime.of(11, 59))).isFalse();
    assertThat(policy.inQuietHours(LocalTime.of(12, 0))).isTrue();
    assertThat(policy.inQuietHours(LocalTime.of(13, 59))).isTrue();
    assertThat(policy.inQuietHours(LocalTime.of(14, 0))).isFalse();
  }

  @Test
  @DisplayName("免打扰起止相同表示全天不免打扰")
  void 起止相同表示不免打扰() {
    EffectiveNagPolicy policy = policy(90, 60, LocalTime.of(0, 0), LocalTime.of(0, 0));

    assertThat(policy.inQuietHours(LocalTime.of(0, 0))).isFalse();
    assertThat(policy.inQuietHours(LocalTime.of(23, 59))).isFalse();
  }

  @Test
  @DisplayName("文案只替换白名单变量，未知占位符原样保留")
  void 文案只替换白名单变量() {
    EffectiveNagPolicy policy =
        new EffectiveNagPolicy(
            5,
            150,
            60,
            90,
            60,
            3,
            10,
            LocalTime.of(23, 30),
            LocalTime.of(7, 0),
            1,
            5,
            true,
            true,
            "{{user}}/{{pending}}/{{idleMinutes}}/{{date}}/{{secret}}",
            true,
            true,
            true);

    assertThat(policy.renderMessage("张三", 3, 95, "2026-09-07"))
        .isEqualTo("张三/3/95/2026-09-07/{{secret}}");
  }

  @Test
  @DisplayName("宽限期与全屏超时按秒与分钟换算为 Duration")
  void 时长字段换算() {
    EffectiveNagPolicy policy = policy(90, 60, LocalTime.of(23, 30), LocalTime.of(7, 0));

    assertThat(policy.presenceGrace()).isEqualTo(Duration.ofSeconds(150));
    assertThat(policy.fullscreenTimeout()).isEqualTo(Duration.ofMinutes(10));
  }
}
