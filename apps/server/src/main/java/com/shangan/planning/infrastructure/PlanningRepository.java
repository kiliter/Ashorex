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

  void insertPlan(DailyPlan plan, Instant now);

  void insertItem(PlanItem item, Instant now);

  void updateItemDefinition(
      String itemId, String title, long plannedSeconds, int sortOrder, Instant now);

  void deleteItem(String itemId);

  void snapshotQuizRequired(String itemId, boolean required, Instant now);

  void updatePlanState(DailyPlan plan, Instant now);

  void insertAbandonment(
      String id,
      String planId,
      String userId,
      String reasonCode,
      String reasonText,
      long remainingSeconds,
      Instant now);

  List<DuePlan> findLockedPlans();

  ProgressUpdate updateAbsoluteProgress(String userId, String itemId, long absolute, Instant now);

  ProgressUpdate updateVideoWatchProgress(
      String userId, String itemId, long absolute, boolean watchCompleted, Instant now);

  ProgressUpdate markQuizCompleted(String userId, String itemId, Instant now);

  void markItemCompletedIfSatisfied(String itemId, Instant now);

  record DuePlan(
      String planId, String userId, LocalDate planDate, String timezone, String dayEndLocalTime) {}

  record ProgressUpdate(PlanItem before, PlanItem after, long positiveDelta) {}
}
