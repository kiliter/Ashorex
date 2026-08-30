package com.shangan.exam.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** 纯计算考试倒计时和进度压力，不修改计划或课程数据。 */
@Component
public class ExamProgressCalculator {

  private final Clock clock;

  public ExamProgressCalculator(Clock clock) {
    this.clock = clock;
  }

  /** 根据用户时区、课程完成量和最近七天完成量生成只读压力信息。 */
  public Progress calculate(
      LocalDate examDate,
      LocalDate targetCompletionDate,
      ZoneId timezone,
      int totalLessons,
      int completedLessons,
      int completedInLastSevenDays) {
    LocalDate today = LocalDate.now(clock.withZone(timezone));
    int safeTotal = Math.max(0, totalLessons);
    int safeCompleted = Math.min(safeTotal, Math.max(0, completedLessons));
    int remaining = safeTotal - safeCompleted;
    long daysUntilExam = ChronoUnit.DAYS.between(today, examDate);
    long daysUntilTarget = ChronoUnit.DAYS.between(today, targetCompletionDate);
    double requiredPace = remaining == 0 ? 0 : remaining / (double) Math.max(1, daysUntilTarget);
    double actualPace = Math.max(0, completedInLastSevenDays) / 7.0;
    LocalDate projectedDate =
        remaining == 0
            ? today
            : actualPace <= 0 ? null : today.plusDays((long) Math.ceil(remaining / actualPace));
    boolean onTrack =
        remaining == 0
            || (actualPace >= requiredPace
                && projectedDate != null
                && !projectedDate.isAfter(targetCompletionDate));
    return new Progress(
        daysUntilExam,
        daysUntilTarget,
        safeTotal,
        safeCompleted,
        remaining,
        requiredPace,
        actualPace,
        projectedDate,
        onTrack ? "ON_TRACK" : "AT_RISK");
  }

  /** API 可直接序列化的考试压力结果。 */
  public record Progress(
      long daysUntilExam,
      long daysUntilTarget,
      int totalLessons,
      int completedLessons,
      int remainingLessons,
      double requiredDailyPace,
      double actualDailyPace,
      LocalDate projectedFinishDate,
      String riskStatus) {}
}
