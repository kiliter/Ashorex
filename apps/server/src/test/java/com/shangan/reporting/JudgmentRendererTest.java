package com.shangan.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.reporting.application.JudgmentRenderer;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 晚间审判完全由固定规则生成，输出不依赖 AI 或随机性。 */
class JudgmentRendererTest {
  private final JudgmentRenderer renderer = new JudgmentRenderer();

  @Test
  void rendersCompletionBandsAndDebtGrowthDeterministically() {
    String strong = renderer.render(facts(95, false, 0, false));
    String medium = renderer.render(facts(75, false, 600, false));
    String weak = renderer.render(facts(40, false, 1800, true));

    assertThat(strong).isEqualTo("今日计划完成率 95%，完成得很扎实。继续保持可信学习节奏。");
    assertThat(medium).isEqualTo("今日计划完成率 75%，还有 600 秒任务转为欠债。明天优先处理未完成项。");
    assertThat(weak).isEqualTo("今日计划完成率仅 40%，新增欠债 1800 秒。欠债已连续 3 天增长，请立即收缩目标并优先还债。");
  }

  @Test
  void abandonmentIncludesTimeReasonAndExactAddedDebt() {
    var facts =
        new JudgmentRenderer.Facts(
            50, true, Instant.parse("2026-08-30T12:34:00Z"), "今天状态很差", 2400, false);

    assertThat(renderer.render(facts))
        .isEqualTo("你在 12:34 选择开摆，原因：今天状态很差。今日新增欠债 2400 秒，计划已不可撤销地关闭。");
  }

  private JudgmentRenderer.Facts facts(
      int completionRate, boolean abandoned, long newDebtSeconds, boolean growingThreeDays) {
    return new JudgmentRenderer.Facts(
        completionRate, abandoned, null, "", newDebtSeconds, growingThreeDays);
  }
}
