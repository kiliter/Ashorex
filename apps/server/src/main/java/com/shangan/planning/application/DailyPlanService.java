package com.shangan.planning.application;

import com.shangan.common.api.BusinessException;
import com.shangan.debt.application.DebtService;
import com.shangan.debt.domain.LearningDebt;
import com.shangan.planning.domain.DailyPlan;
import com.shangan.planning.domain.PlanItem;
import com.shangan.planning.domain.PlanStatus;
import com.shangan.planning.infrastructure.PlanningRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日计划事务边界：日终关闭、任务进度对账与还债核销。
 *
 * <p>作战单的读取与整单保存由 {@link BattleOrderService} 负责；按 ADR-0011 与 V1 设计规范第 243 节，客户端不再提供计划逐项编辑、
 * 手动锁定和手动开摆入口，历史 {@code ABANDONED} 数据只读兼容。
 */
@Service
public class DailyPlanService implements PlanProgressPort, DayEndPlanCloser {
  private final PlanningRepository plans;
  private final DebtService debts;
  private final List<ActiveLearningCloser> learningClosers;
  private final Clock clock;

  public DailyPlanService(
      PlanningRepository plans,
      DebtService debts,
      List<ActiveLearningCloser> learningClosers,
      Clock clock) {
    this.plans = plans;
    this.debts = debts;
    this.learningClosers = learningClosers;
    this.clock = clock;
  }

  /** 由独立调度器调用；重复调用已关闭计划时不产生任何新欠债。 */
  @Transactional
  @Override
  public void closeForDayEnd(String userId, String planId) {
    DailyPlan plan = plans.findOwnedPlan(userId, planId).orElseThrow(() -> notFound("计划不存在"));
    if (plan.status() != PlanStatus.LOCKED) return;
    Instant now = clock.instant();
    learningClosers.forEach(closer -> closer.closeForPlan(userId, plan.id(), now));
    List<PlanItem> items = plans.findItems(plan.id());
    if (items.stream()
        .filter(item -> !item.itemType().equals("REVIEW_SHORTCUT"))
        .allMatch(item -> item.status().equals("COMPLETED"))) {
      plan.complete(now);
    } else {
      plan.closeWithDebt(now);
      debts.generate(userId, plan.planDate(), "DAY_END", items, now);
    }
    plans.updatePlanState(plan, now);
  }

  @Override
  @Transactional
  public void updateProgress(String userId, String planItemId, long absoluteCompletedSeconds) {
    var update =
        plans.updateAbsoluteProgress(userId, planItemId, absoluteCompletedSeconds, clock.instant());
    debts.reconcileRepayment(
        userId, update.after().debtId(), planItemId, update.positiveDelta(), clock.instant());
    completePlanIfSatisfied(userId, update.after().planId());
  }

  @Override
  @Transactional
  public void updateVideoWatchProgress(
      String userId, String planItemId, long absoluteCompletedSeconds, boolean watchCompleted) {
    if (planItemId == null) return;
    PlanItem item = plans.findOwnedItem(userId, planItemId).orElseThrow(() -> notFound("计划任务不存在"));
    long taskAbsolute = absoluteCompletedSeconds;
    if (item.itemType().equals("DEBT_REPAYMENT")) {
      LearningDebt debt = requireOpenVideoDebt(userId, item.debtId());
      long effectiveBaseline =
          debt.baselineCompletedSeconds() + debt.originalSeconds() - item.plannedSeconds();
      taskAbsolute =
          watchCompleted
              ? item.plannedSeconds()
              : Math.max(0, absoluteCompletedSeconds - effectiveBaseline);
    }
    var update =
        plans.updateVideoWatchProgress(
            userId, planItemId, taskAbsolute, watchCompleted, clock.instant());
    debts.reconcileRepayment(
        userId, update.after().debtId(), planItemId, update.positiveDelta(), clock.instant());
    completePlanIfSatisfied(userId, update.after().planId());
  }

  @Override
  @Transactional
  public void markQuizCompleted(String userId, String planItemId) {
    PlanItem item = plans.findOwnedItem(userId, planItemId).orElseThrow(() -> notFound("计划任务不存在"));
    Instant now = clock.instant();
    var update = plans.markQuizCompleted(userId, planItemId, now);
    if (item.itemType().equals("DEBT_REPAYMENT") || item.itemType().equals("QUIZ")) {
      update = plans.updateAbsoluteProgress(userId, planItemId, item.plannedSeconds(), now);
      debts.reconcileRepayment(
          userId, update.after().debtId(), planItemId, update.positiveDelta(), now);
    }
    completePlanIfSatisfied(userId, update.after().planId());
  }

  /** 只接受普通视频题、独立题目任务或精确关联 QUIZ 欠债的还债任务。 */
  @Override
  @Transactional(readOnly = true)
  public void validateQuizLink(String userId, String planItemId, String mediaItemId) {
    if (planItemId == null) return;
    PlanItem item = plans.findOwnedItem(userId, planItemId).orElseThrow(() -> notFound("计划任务不存在"));
    boolean supported = item.itemType().equals("VIDEO") || item.itemType().equals("QUIZ");
    if (item.itemType().equals("DEBT_REPAYMENT")) {
      LearningDebt debt =
          debts
              .findDebt(userId, item.debtId())
              .orElseThrow(() -> invalid("PLAN_DEBT_INVALID", "关联的答题欠债不存在"));
      supported = debt.debtType().equals("QUIZ") && mediaItemId.equals(debt.mediaItemId());
    }
    if (!supported || !mediaItemId.equals(item.mediaItemId())) {
      throw invalid("PLAN_ITEM_MEDIA_MISMATCH", "计划任务与答题课时不匹配");
    }
  }

  @Override
  @Transactional
  public void updateFocusProgress(
      String userId, String planItemId, long absoluteCompletedSeconds, boolean completed) {
    if (planItemId == null) return;
    PlanItem item = plans.findOwnedItem(userId, planItemId).orElseThrow(() -> notFound("计划任务不存在"));
    long absolute = completed ? item.plannedSeconds() : absoluteCompletedSeconds;
    var update = plans.updateAbsoluteProgress(userId, planItemId, absolute, clock.instant());
    debts.reconcileRepayment(
        userId, update.after().debtId(), planItemId, update.positiveDelta(), clock.instant());
    completePlanIfSatisfied(userId, update.after().planId());
  }

  @Override
  @Transactional(readOnly = true)
  public void validateFocusLink(String userId, String planItemId) {
    if (planItemId == null) return;
    PlanItem item = plans.findOwnedItem(userId, planItemId).orElseThrow(() -> notFound("计划任务不存在"));
    boolean supported = item.itemType().equals("FOCUS");
    if (item.itemType().equals("DEBT_REPAYMENT")) {
      LearningDebt debt =
          debts
              .findDebt(userId, item.debtId())
              .orElseThrow(() -> invalid("PLAN_DEBT_INVALID", "关联的专注欠债不存在"));
      supported = debt.debtType().equals("FOCUS");
    }
    if (!supported) {
      throw invalid("PLAN_ITEM_FOCUS_MISMATCH", "计划任务不是专注任务");
    }
  }

  @Transactional(readOnly = true)
  public PlanSummary todaySummary(String userId, String timezone) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(timezone)));
    return plans
        .findPlan(userId, today)
        .map(
            plan -> {
              List<PlanItem> items = plans.findItems(plan.id());
              return new PlanSummary(
                  plan.status().name(),
                  items.stream().mapToLong(PlanItem::plannedSeconds).sum(),
                  items.stream().mapToLong(PlanItem::completedSeconds).sum());
            })
        .orElse(new PlanSummary("NONE", 0, 0));
  }

  public boolean isDue(PlanningRepository.DuePlan plan) {
    var localNow = clock.instant().atZone(ZoneId.of(plan.timezone()));
    return plan.planDate().isBefore(localNow.toLocalDate())
        || (plan.planDate().equals(localNow.toLocalDate())
            && !localNow.toLocalTime().isBefore(LocalTime.parse(plan.dayEndLocalTime())));
  }

  public List<PlanningRepository.DuePlan> lockedPlans() {
    return plans.findLockedPlans();
  }

  /** 学习模块创建会话前校验计划任务所有权、类型和关联课时。 */
  @Transactional(readOnly = true)
  public void validateVideoLink(String userId, String planItemId, String mediaItemId) {
    if (planItemId == null) return;
    PlanItem item = plans.findOwnedItem(userId, planItemId).orElseThrow(() -> notFound("计划任务不存在"));
    boolean videoTask =
        item.itemType().equals("VIDEO") || item.itemType().equals("REVIEW_SHORTCUT");
    boolean videoDebtTask =
        item.itemType().equals("DEBT_REPAYMENT")
            && requireOpenVideoDebt(userId, item.debtId()).debtType().equals("VIDEO_WATCH");
    if ((!videoTask && !videoDebtTask) || !mediaItemId.equals(item.mediaItemId())) {
      throw invalid("PLAN_ITEM_MEDIA_MISMATCH", "计划任务与课时不匹配");
    }
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isReviewShortcut(String userId, String planItemId, String mediaItemId) {
    if (planItemId == null) return false;
    return plans
        .findOwnedItem(userId, planItemId)
        .filter(item -> item.itemType().equals("REVIEW_SHORTCUT"))
        .filter(item -> mediaItemId.equals(item.mediaItemId()))
        .isPresent();
  }

  private LearningDebt requireOpenVideoDebt(String userId, String debtId) {
    return debts.openDebts(userId).stream()
        .filter(debt -> debt.id().equals(debtId) && debt.debtType().equals("VIDEO_WATCH"))
        .findFirst()
        .orElseThrow(() -> invalid("PLAN_DEBT_INVALID", "关联的视频欠债不可用"));
  }

  private void completePlanIfSatisfied(String userId, String planId) {
    DailyPlan plan = plans.findOwnedPlan(userId, planId).orElseThrow();
    if (!plans.isActiveBattleOrder(planId)
        && plan.status() == PlanStatus.LOCKED
        && plans.findItems(planId).stream().allMatch(item -> item.status().equals("COMPLETED"))) {
      plan.complete(clock.instant());
      plans.updatePlanState(plan, clock.instant());
    }
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  private BusinessException notFound(String message) {
    return new BusinessException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", message);
  }

  public record PlanSummary(String status, long plannedSeconds, long completedSeconds) {}
}
