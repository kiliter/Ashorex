package com.shangan.planning.domain;

import com.shangan.common.api.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** 每日计划领域状态机，所有终态都不可逆。 */
public final class DailyPlan {

  private final String id;
  private final String userId;
  private final LocalDate planDate;
  private final Set<String> itemIds;
  private PlanStatus status;
  private Instant lockedAt;
  private Instant closedAt;

  private DailyPlan(
      String id,
      String userId,
      LocalDate planDate,
      PlanStatus status,
      Set<String> itemIds,
      Instant lockedAt,
      Instant closedAt) {
    this.id = id;
    this.userId = userId;
    this.planDate = planDate;
    this.status = status;
    this.itemIds = new LinkedHashSet<>(itemIds);
    this.lockedAt = lockedAt;
    this.closedAt = closedAt;
  }

  public static DailyPlan draft(String id, String userId, LocalDate planDate) {
    return new DailyPlan(id, userId, planDate, PlanStatus.DRAFT, Set.of(), null, null);
  }

  public static DailyPlan restore(
      String id,
      String userId,
      LocalDate planDate,
      PlanStatus status,
      Set<String> itemIds,
      Instant lockedAt,
      Instant closedAt) {
    return new DailyPlan(id, userId, planDate, status, itemIds, lockedAt, closedAt);
  }

  public void addItem(String itemId) {
    requireDraft();
    itemIds.add(itemId);
  }

  public void removeItem(String itemId) {
    requireDraft();
    itemIds.remove(itemId);
  }

  public void lock(Instant at) {
    requireDraft();
    if (itemIds.isEmpty()) {
      throw invalid("计划至少添加一个任务后才能锁定");
    }
    status = PlanStatus.LOCKED;
    lockedAt = at;
  }

  public void complete(Instant at) {
    requireLocked();
    status = PlanStatus.COMPLETED;
    closedAt = at;
  }

  public void abandon(Instant at) {
    requireLocked();
    status = PlanStatus.ABANDONED;
    closedAt = at;
  }

  public void closeWithDebt(Instant at) {
    requireLocked();
    status = PlanStatus.CLOSED_WITH_DEBT;
    closedAt = at;
  }

  private void requireDraft() {
    if (status != PlanStatus.DRAFT) {
      throw invalid("计划已锁定，不能再修改");
    }
  }

  private void requireLocked() {
    if (status != PlanStatus.LOCKED) {
      throw invalid("当前计划状态不允许执行该操作");
    }
  }

  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.CONFLICT, "PLAN_ILLEGAL_TRANSITION", message);
  }

  public String id() {
    return id;
  }

  public String userId() {
    return userId;
  }

  public LocalDate planDate() {
    return planDate;
  }

  public PlanStatus status() {
    return status;
  }

  public Set<String> itemIds() {
    return Set.copyOf(itemIds);
  }

  public Instant lockedAt() {
    return lockedAt;
  }

  public Instant closedAt() {
    return closedAt;
  }
}
