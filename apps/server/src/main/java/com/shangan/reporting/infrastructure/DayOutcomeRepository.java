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

  boolean hasEffectiveActivity(String userId, Instant start, Instant end);

  void upsert(String id, String userId, LocalDate date, String outcome, Instant generatedAt);

  record UserDaySettings(String userId, String timezone, String dayEndLocalTime) {}

  record PlanDay(String planId, String lifecycleStatus, int accountableItems) {}
}
