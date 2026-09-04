package com.shangan.planning.infrastructure;

import com.shangan.planning.domain.DailyPlan;
import com.shangan.planning.domain.PlanItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 每日计划持久化边界。 */
public interface PlanningRepository {
  Optional<DailyPlan> findPlan(String userId, LocalDate date);

  Optional<DailyPlan> findOwnedPlan(String userId, String planId);

  List<PlanItem> findItems(String planId);

  Optional<PlanItem> findOwnedItem(String userId, String itemId);

  boolean isActiveBattleOrder(String planId);

  void updatePlanState(DailyPlan plan, Instant now);

  List<DuePlan> findLockedPlans();

  ProgressUpdate updateAbsoluteProgress(String userId, String itemId, long absolute, Instant now);

  ProgressUpdate updateVideoWatchProgress(
      String userId, String itemId, long absolute, boolean watchCompleted, Instant now);

  ProgressUpdate markQuizCompleted(String userId, String itemId, Instant now);

  record DuePlan(
      String planId, String userId, LocalDate planDate, String timezone, String dayEndLocalTime) {}

  record ProgressUpdate(PlanItem before, PlanItem after, long positiveDelta) {}
}
