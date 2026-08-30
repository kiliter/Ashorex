package com.shangan.reporting.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 基于七份确定性日报构建周报，并给出与上一周的绝对变化。 */
@Service
public class WeeklyReportService {
  private final DailyReportService daily;

  public WeeklyReportService(DailyReportService daily) {
    this.daily = daily;
  }

  public WeeklyReportView generate(String userId, LocalDate weekStart) {
    List<DailyReportService.DailyReportView> current = generateWeek(userId, weekStart);
    List<DailyReportService.DailyReportView> previous =
        generateWeek(userId, weekStart.minusWeeks(1));
    Totals totals = totals(current);
    Totals previousTotals = totals(previous);
    List<DayTrend> days =
        current.stream()
            .map(
                report ->
                    new DayTrend(
                        report.date(),
                        report.videoStudySeconds() + report.focusSeconds(),
                        report.completionRate(),
                        report.newDebtSeconds(),
                        report.repaidDebtSeconds()))
            .toList();
    return new WeeklyReportView(
        weekStart,
        days,
        totals.effectiveStudySeconds(),
        totals.videoStudySeconds(),
        totals.focusSeconds(),
        totals.videoCompletedCount(),
        totals.answerCount(),
        totals.answerAccuracy(),
        totals.planCompletionRate(),
        totals.newDebtSeconds(),
        totals.repaidDebtSeconds(),
        totals.abandonmentCount(),
        totals.aliveCheckFailureCount(),
        previousTotals.effectiveStudySeconds(),
        totals.effectiveStudySeconds() - previousTotals.effectiveStudySeconds(),
        previousTotals.planCompletionRate(),
        totals.planCompletionRate() - previousTotals.planCompletionRate());
  }

  private List<DailyReportService.DailyReportView> generateWeek(String userId, LocalDate start) {
    List<DailyReportService.DailyReportView> result = new ArrayList<>();
    for (int offset = 0; offset < 7; offset++) {
      result.add(daily.generate(userId, start.plusDays(offset)));
    }
    return List.copyOf(result);
  }

  private Totals totals(List<DailyReportService.DailyReportView> reports) {
    long video =
        reports.stream().mapToLong(DailyReportService.DailyReportView::videoStudySeconds).sum();
    long focus = reports.stream().mapToLong(DailyReportService.DailyReportView::focusSeconds).sum();
    int answers = reports.stream().mapToInt(DailyReportService.DailyReportView::answerCount).sum();
    int correct =
        reports.stream().mapToInt(DailyReportService.DailyReportView::correctAnswerCount).sum();
    int completedTasks =
        reports.stream().mapToInt(DailyReportService.DailyReportView::completedTasks).sum();
    int totalTasks =
        reports.stream().mapToInt(DailyReportService.DailyReportView::totalTasks).sum();
    return new Totals(
        video + focus,
        video,
        focus,
        reports.stream().mapToInt(DailyReportService.DailyReportView::videoCompletedCount).sum(),
        answers,
        answers == 0 ? 0 : (int) Math.round(correct * 100.0 / answers),
        totalTasks == 0 ? 0 : (int) Math.round(completedTasks * 100.0 / totalTasks),
        reports.stream().mapToLong(DailyReportService.DailyReportView::newDebtSeconds).sum(),
        reports.stream().mapToLong(DailyReportService.DailyReportView::repaidDebtSeconds).sum(),
        (int) reports.stream().filter(DailyReportService.DailyReportView::abandoned).count(),
        reports.stream()
            .mapToInt(DailyReportService.DailyReportView::aliveCheckFailureCount)
            .sum());
  }

  public record DayTrend(
      LocalDate date,
      long effectiveStudySeconds,
      int completionRate,
      long newDebtSeconds,
      long repaidDebtSeconds) {}

  public record WeeklyReportView(
      LocalDate weekStart,
      List<DayTrend> days,
      long totalEffectiveStudySeconds,
      long videoStudySeconds,
      long focusSeconds,
      int videoCompletedCount,
      int answerCount,
      int answerAccuracy,
      int planCompletionRate,
      long newDebtSeconds,
      long repaidDebtSeconds,
      int abandonmentCount,
      int aliveCheckFailureCount,
      long previousWeekEffectiveStudySeconds,
      long effectiveStudySecondsChange,
      int previousWeekPlanCompletionRate,
      int planCompletionRateChange) {}

  private record Totals(
      long effectiveStudySeconds,
      long videoStudySeconds,
      long focusSeconds,
      int videoCompletedCount,
      int answerCount,
      int answerAccuracy,
      int planCompletionRate,
      long newDebtSeconds,
      long repaidDebtSeconds,
      int abandonmentCount,
      int aliveCheckFailureCount) {}
}
