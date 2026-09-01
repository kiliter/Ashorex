package com.shangan.planning.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** V1.3 作战单完整快照持久化边界，与历史逐项计划接口隔离。 */
public interface BattleOrderRepository {
  Optional<PlanRow> findPlan(String userId, LocalDate date);

  List<ItemRow> findItems(String planId);

  void insertPlan(String id, String userId, LocalDate date, Instant now);

  boolean activateAndIncrement(String planId, long expectedVersion, Instant now);

  void deleteMutableItems(String planId);

  void insertItem(ItemDraft item, Instant now);

  /** 只修正展示顺序，不改变项目身份、执行状态或完成进度。 */
  void updateItemSortOrder(String planId, String itemId, int sortOrder, Instant now);

  void insertRevision(String id, String planId, long version, String snapshotJson, Instant now);

  boolean isLessonCompleted(String userId, String mediaItemId);

  record PlanRow(String id, String userId, LocalDate date, String lifecycleStatus, long version) {}

  record ItemRow(
      String id,
      String planId,
      String itemType,
      String title,
      String mediaItemId,
      String mockExamPresetId,
      String mockExamNameSnapshot,
      long plannedSeconds,
      long completedSeconds,
      String status,
      int sortOrder,
      boolean immutable) {}

  record ItemDraft(
      String id,
      String planId,
      String logicalType,
      String physicalType,
      String title,
      String mediaItemId,
      String mockExamPresetId,
      String mockExamNameSnapshot,
      long plannedSeconds,
      boolean quizRequired,
      int sortOrder) {}
}
