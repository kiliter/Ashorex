package com.shangan.reporting.application;

import com.shangan.common.IdGenerator;
import com.shangan.reporting.infrastructure.ReportingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 按用户本地日聚合确定性日报，并把可恢复快照落入 SQLite。 */
@Service
public class DailyReportService {
  private final ReportingRepository reports;
  private final JudgmentRenderer judgments;
  private final ObjectMapper json;
  private final IdGenerator ids;
  private final Clock clock;

  public DailyReportService(
      ReportingRepository reports,
      JudgmentRenderer judgments,
      ObjectMapper json,
      IdGenerator ids,
      Clock clock) {
    this.reports = reports;
    this.judgments = judgments;
    this.json = json;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public DailyReportView generate(String userId, LocalDate date) {
    String timezone = reports.timezone(userId);
    ZoneId zone = ZoneId.of(timezone);
    Instant start = date.atStartOfDay(zone).toInstant();
    Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
    var raw = reports.aggregate(userId, date, start, end);
    int completionRate =
        raw.totalTasks() == 0
            ? 0
            : (int) Math.round(raw.completedTasks() * 100.0 / raw.totalTasks());
    int answerAccuracy =
        raw.answerCount() == 0
            ? 0
            : (int) Math.round(raw.correctAnswerCount() * 100.0 / raw.answerCount());
    Instant generatedAt = clock.instant();
    String judgment =
        judgments.render(
            new JudgmentRenderer.Facts(
                completionRate,
                raw.abandoned(),
                raw.abandonedAt(),
                raw.abandonmentReason(),
                raw.newDebtSeconds(),
                reports.debtGrewOnEachOfThreeDays(userId, date),
                timezone));
    DailyReportView view =
        new DailyReportView(
            date,
            raw.planStatus(),
            raw.plannedSeconds(),
            raw.videoStudySeconds(),
            raw.focusSeconds(),
            raw.completedTasks(),
            raw.totalTasks(),
            completionRate,
            raw.videoCompletedCount(),
            raw.answerCount(),
            raw.correctAnswerCount(),
            answerAccuracy,
            raw.aliveCheckFailureCount(),
            raw.abandoned(),
            raw.newDebtSeconds(),
            raw.repaidDebtSeconds(),
            raw.openDebtSeconds(),
            judgment,
            generatedAt);
    reports.upsert(ids.nextId(), userId, date, toJson(view), judgment, generatedAt);
    return view;
  }

  private String toJson(DailyReportView view) {
    try {
      return json.writeValueAsString(view);
    } catch (Exception error) {
      throw new IllegalStateException("日报快照序列化失败", error);
    }
  }

  public record DailyReportView(
      LocalDate date,
      String planStatus,
      long plannedSeconds,
      long videoStudySeconds,
      long focusSeconds,
      int completedTasks,
      int totalTasks,
      int completionRate,
      int videoCompletedCount,
      int answerCount,
      int correctAnswerCount,
      int answerAccuracy,
      int aliveCheckFailureCount,
      boolean abandoned,
      long newDebtSeconds,
      long repaidDebtSeconds,
      long openDebtSeconds,
      String judgmentText,
      Instant generatedAt) {}
}
