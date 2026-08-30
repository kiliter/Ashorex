package com.shangan.reporting.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 报表原始指标和快照持久化边界。 */
public interface ReportingRepository {
  String timezone(String userId);

  RawDailyMetrics aggregate(String userId, LocalDate date, Instant start, Instant end);

  boolean debtGrewOnEachOfThreeDays(String userId, LocalDate date);

  void upsert(
      String id,
      String userId,
      LocalDate date,
      String payloadJson,
      String judgmentText,
      Instant generatedAt);

  Optional<Snapshot> find(String userId, LocalDate date);

  List<ReportCandidate> terminalPlans();

  List<UserTimezone> users();

  record RawDailyMetrics(
      String planStatus,
      long plannedSeconds,
      int completedTasks,
      int totalTasks,
      long videoStudySeconds,
      long focusSeconds,
      int videoCompletedCount,
      int answerCount,
      int correctAnswerCount,
      int aliveCheckFailureCount,
      boolean abandoned,
      Instant abandonedAt,
      String abandonmentReason,
      long newDebtSeconds,
      long repaidDebtSeconds,
      long openDebtSeconds) {}

  record Snapshot(String payloadJson, String judgmentText, Instant generatedAt) {}

  record ReportCandidate(String userId, LocalDate date) {}

  record UserTimezone(String userId, String timezone) {}
}
