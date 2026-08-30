package com.shangan.planning.application;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.common.IdGenerator;
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
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每日计划事务边界，统一处理锁定、关闭、完成与还债对账。 */
@Service
public class DailyPlanService implements PlanProgressPort {
  private final PlanningRepository plans;
  private final DebtService debts;
  private final CatalogQueryService catalog;
  private final List<ActiveLearningCloser> learningClosers;
  private final List<VideoTaskRequirementPort> videoRequirements;
  private final IdGenerator ids;
  private final Clock clock;

  public DailyPlanService(
      PlanningRepository plans,
      DebtService debts,
      CatalogQueryService catalog,
      List<ActiveLearningCloser> learningClosers,
      List<VideoTaskRequirementPort> videoRequirements,
      IdGenerator ids,
      Clock clock) {
    this.plans = plans;
    this.debts = debts;
    this.catalog = catalog;
    this.learningClosers = learningClosers;
    this.videoRequirements = videoRequirements;
    this.ids = ids;
    this.clock = clock;
  }

  /** 读取指定日期计划；尚未创建时以短事务创建唯一 DRAFT。 */
  @Transactional
  public PlanView getOrCreate(String userId, LocalDate date) {
    DailyPlan plan =
        plans
            .findPlan(userId, date)
            .orElseGet(
                () -> {
                  DailyPlan created = DailyPlan.draft(ids.nextId(), userId, date);
                  plans.insertPlan(created, clock.instant());
                  return created;
                });
    return view(plan);
  }

  @Transactional
  public PlanView addItem(String userId, LocalDate date, ItemDraft draft) {
    DailyPlan plan = getOrCreatePlan(userId, date);
    String type = validateType(draft.itemType());
    String mediaId = draft.mediaItemId();
    String debtId = draft.debtId();
    long plannedSeconds = draft.plannedSeconds();
    String title = draft.title().trim();
    if (type.equals("VIDEO")) {
      var lesson =
          catalog.findLesson(mediaId).orElseThrow(() -> invalid("PLAN_MEDIA_INVALID", "所选课时不可用"));
      title = lesson.title();
      plannedSeconds = Math.max(1, (lesson.durationMs() + 999) / 1000);
    } else if (type.equals("DEBT_REPAYMENT")) {
      LearningDebt debt =
          debts.openDebts(userId).stream()
              .filter(value -> value.id().equals(debtId))
              .findFirst()
              .orElseThrow(() -> invalid("PLAN_DEBT_INVALID", "所选欠债不可用"));
      title = "还债：" + debt.title();
      plannedSeconds = debt.remainingSeconds();
      mediaId = debt.mediaItemId();
    } else if (plannedSeconds <= 0 || title.isBlank()) {
      throw invalid("PLAN_ITEM_INVALID", "任务名称和计划时长不能为空");
    }
    PlanItem item =
        new PlanItem(
            ids.nextId(),
            plan.id(),
            type,
            title,
            mediaId,
            debtId,
            plannedSeconds,
            0,
            false,
            false,
            false,
            "PENDING",
            draft.sortOrder(),
            null);
    plan.addItem(item.id());
    plans.insertItem(item, clock.instant());
    return view(plan);
  }

  /** 在一个事务中把选定欠债加入 DRAFT 计划，不自动锁定。 */
  @Transactional
  public PlanView addDebtItems(String userId, LocalDate date, List<String> debtIds) {
    PlanView result = getOrCreate(userId, date);
    for (String debtId : new java.util.LinkedHashSet<>(debtIds)) {
      result =
          addItem(
              userId,
              date,
              new ItemDraft("DEBT_REPAYMENT", "还债", null, debtId, 1, result.items().size()));
    }
    return result;
  }

  @Transactional
  public PlanView updateItem(
      String userId,
      LocalDate date,
      String itemId,
      String title,
      long plannedSeconds,
      int sortOrder) {
    DailyPlan plan = requirePlan(userId, date);
    plan.addItem(itemId);
    PlanItem item = requireOwnedItem(userId, itemId, plan.id());
    if (item.itemType().equals("VIDEO") || item.itemType().equals("DEBT_REPAYMENT")) {
      plannedSeconds = item.plannedSeconds();
    }
    if (plannedSeconds <= 0 || title.isBlank()) throw invalid("PLAN_ITEM_INVALID", "任务内容无效");
    plans.updateItemDefinition(itemId, title.trim(), plannedSeconds, sortOrder, clock.instant());
    return view(plan);
  }

  @Transactional
  public PlanView deleteItem(String userId, LocalDate date, String itemId) {
    DailyPlan plan = requirePlan(userId, date);
    requireOwnedItem(userId, itemId, plan.id());
    plan.removeItem(itemId);
    plans.deleteItem(itemId);
    return view(plan);
  }

  @Transactional
  public PlanView lock(String userId, LocalDate date) {
    DailyPlan plan = requirePlan(userId, date);
    List<PlanItem> items = plans.findItems(plan.id());
    for (PlanItem item : items) {
      if (!item.itemType().equals("VIDEO")) continue;
      boolean required =
          videoRequirements.stream().anyMatch(port -> port.quizRequired(item.mediaItemId()));
      plans.snapshotQuizRequired(item.id(), required, clock.instant());
    }
    plan.lock(clock.instant());
    plans.updatePlanState(plan, clock.instant());
    return view(plan);
  }

  @Transactional(readOnly = true)
  public AbandonPreview previewAbandon(String userId, LocalDate date) {
    DailyPlan plan = requirePlan(userId, date);
    if (plan.status() != PlanStatus.LOCKED) throw invalid("PLAN_NOT_LOCKED", "计划尚未锁定");
    List<DebtPreview> additions = calculateDebtPreview(plans.findItems(plan.id()));
    return new AbandonPreview(
        additions.size(), additions.stream().mapToLong(DebtPreview::seconds).sum(), additions);
  }

  /** 开摆原子结束活动会话、记录原因、关闭计划并生成欠债。 */
  @Transactional
  public PlanView abandon(String userId, LocalDate date, String reasonCode, String reasonText) {
    DailyPlan plan = requirePlan(userId, date);
    if (plan.status() == PlanStatus.ABANDONED) return view(plan);
    if (plan.status() != PlanStatus.LOCKED) throw invalid("PLAN_NOT_LOCKED", "仅锁定计划可以开摆");
    Instant now = clock.instant();
    learningClosers.forEach(closer -> closer.closeForPlan(userId, plan.id(), now));
    List<PlanItem> items = plans.findItems(plan.id());
    long remaining = calculateDebtPreview(items).stream().mapToLong(DebtPreview::seconds).sum();
    plans.insertAbandonment(
        ids.nextId(), plan.id(), userId, reasonCode, reasonText.trim(), remaining, now);
    plan.abandon(now);
    plans.updatePlanState(plan, now);
    debts.generate(userId, date, "ABANDONED", items, now);
    return view(plan);
  }

  /** 由独立调度器调用；重复调用已关闭计划时不产生任何新欠债。 */
  @Transactional
  public void closeForDayEnd(String userId, String planId) {
    DailyPlan plan = plans.findOwnedPlan(userId, planId).orElseThrow(() -> notFound("计划不存在"));
    if (plan.status() != PlanStatus.LOCKED) return;
    Instant now = clock.instant();
    learningClosers.forEach(closer -> closer.closeForPlan(userId, plan.id(), now));
    List<PlanItem> items = plans.findItems(plan.id());
    if (items.stream().allMatch(item -> item.status().equals("COMPLETED"))) {
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
  public void markQuizCompleted(String userId, String planItemId) {
    var update = plans.markQuizCompleted(userId, planItemId, clock.instant());
    completePlanIfSatisfied(userId, update.after().planId());
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

  private void completePlanIfSatisfied(String userId, String planId) {
    DailyPlan plan = plans.findOwnedPlan(userId, planId).orElseThrow();
    if (plan.status() == PlanStatus.LOCKED
        && plans.findItems(planId).stream().allMatch(item -> item.status().equals("COMPLETED"))) {
      plan.complete(clock.instant());
      plans.updatePlanState(plan, clock.instant());
    }
  }

  private List<DebtPreview> calculateDebtPreview(List<PlanItem> items) {
    List<DebtPreview> result = new ArrayList<>();
    for (PlanItem item : items) {
      if (item.itemType().equals("DEBT_REPAYMENT")) continue;
      long remaining = Math.max(0, item.plannedSeconds() - item.completedSeconds());
      if (item.itemType().equals("VIDEO") && !item.watchCompleted() && remaining > 0) {
        result.add(new DebtPreview("VIDEO_WATCH", item.title(), remaining));
      }
      if (item.itemType().equals("VIDEO") && item.quizRequired() && !item.quizCompleted()) {
        result.add(new DebtPreview("QUIZ", item.title(), 600));
      }
      if (item.itemType().equals("FOCUS") && remaining > 0) {
        result.add(new DebtPreview("FOCUS", item.title(), remaining));
      }
    }
    return result;
  }

  private DailyPlan getOrCreatePlan(String userId, LocalDate date) {
    return plans
        .findPlan(userId, date)
        .orElseGet(
            () -> {
              DailyPlan plan = DailyPlan.draft(ids.nextId(), userId, date);
              plans.insertPlan(plan, clock.instant());
              return plan;
            });
  }

  private DailyPlan requirePlan(String userId, LocalDate date) {
    return plans.findPlan(userId, date).orElseThrow(() -> notFound("计划不存在"));
  }

  private PlanItem requireOwnedItem(String userId, String itemId, String planId) {
    PlanItem item = plans.findOwnedItem(userId, itemId).orElseThrow(() -> notFound("计划任务不存在"));
    if (!item.planId().equals(planId)) throw notFound("计划任务不存在");
    return item;
  }

  private PlanView view(DailyPlan plan) {
    return new PlanView(
        plan.id(), plan.planDate(), plan.status().name(), plans.findItems(plan.id()));
  }

  private String validateType(String type) {
    if (!List.of("VIDEO", "FOCUS", "QUIZ", "DEBT_REPAYMENT").contains(type)) {
      throw invalid("PLAN_ITEM_TYPE_INVALID", "任务类型无效");
    }
    return type;
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  private BusinessException notFound(String message) {
    return new BusinessException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", message);
  }

  public record ItemDraft(
      String itemType,
      String title,
      String mediaItemId,
      String debtId,
      long plannedSeconds,
      int sortOrder) {}

  public record PlanView(String id, LocalDate date, String status, List<PlanItem> items) {}

  public record PlanSummary(String status, long plannedSeconds, long completedSeconds) {}

  public record DebtPreview(String type, String title, long seconds) {}

  public record AbandonPreview(int debtCount, long addedDebtSeconds, List<DebtPreview> debts) {}
}
