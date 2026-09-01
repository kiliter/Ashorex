package com.shangan.reporting.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 自动日终结果持久化边界。 */
public interface DayOutcomeRepository {
  List<UserDaySettings> users();

  Optional<LocalDate> latestOutcomeDate(String userId);

  Optional<PlanDay> findPlan(String userId, LocalDate date);

  DayActivitySummary activitySummary(String userId, Instant start, Instant end);

  void upsert(String id, String userId, LocalDate date, String outcome, Instant generatedAt);

  record UserDaySettings(String userId, String timezone, String dayEndLocalTime) {}

  record PlanDay(String planId, String lifecycleStatus, int accountableItems) {}

  /** 用户自然日活动汇总；复习次数只用于审计，不参与有效学习判断。 */
  record DayActivitySummary(
      int trustedWatchSessions, int focusSessions, int mockExamSessions, int reviewEvents) {

    /** 判断是否存在可以把无作战单日期归类为自由学习的有效活动。 */
    public boolean hasEffectiveActivity() {
      return trustedWatchSessions > 0 || focusSessions > 0 || mockExamSessions > 0;
    }
  }
}
