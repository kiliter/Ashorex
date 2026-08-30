package com.shangan.reporting.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** 根据冻结规则生成晚间审判，不调用模型，也不包含随机文案。 */
@Component
public class JudgmentRenderer {
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

  public String render(Facts facts) {
    if (facts.abandoned()) {
      String reason = facts.abandonmentReason().isBlank() ? "未填写" : facts.abandonmentReason();
      return "你在 "
          + TIME.withZone(ZoneId.of(facts.timezone())).format(facts.abandonedAt())
          + " 选择开摆，原因："
          + reason
          + "。今日新增欠债 "
          + facts.newDebtSeconds()
          + " 秒，计划已不可撤销地关闭。";
    }
    if (facts.completionRate() >= 90) {
      return "今日计划完成率 " + facts.completionRate() + "%，完成得很扎实。继续保持可信学习节奏。";
    }
    if (facts.completionRate() >= 60) {
      return "今日计划完成率 "
          + facts.completionRate()
          + "%，还有 "
          + facts.newDebtSeconds()
          + " 秒任务转为欠债。明天优先处理未完成项。";
    }
    String growth = facts.debtGrowingThreeDays() ? "欠债已连续 3 天增长，请立即收缩目标并优先还债。" : "请立即收缩目标并优先还债。";
    return "今日计划完成率仅 "
        + facts.completionRate()
        + "%，新增欠债 "
        + facts.newDebtSeconds()
        + " 秒。"
        + growth;
  }

  public record Facts(
      int completionRate,
      boolean abandoned,
      Instant abandonedAt,
      String abandonmentReason,
      long newDebtSeconds,
      boolean debtGrowingThreeDays,
      String timezone) {
    /** 纯规则单元测试默认使用 UTC；生产调用显式传用户时区。 */
    public Facts(
        int completionRate,
        boolean abandoned,
        Instant abandonedAt,
        String abandonmentReason,
        long newDebtSeconds,
        boolean debtGrowingThreeDays) {
      this(
          completionRate,
          abandoned,
          abandonedAt,
          abandonmentReason,
          newDebtSeconds,
          debtGrowingThreeDays,
          ZoneOffset.UTC.getId());
    }
  }
}
