package com.shangan.debt.application;

import com.shangan.common.IdGenerator;
import com.shangan.debt.domain.LearningDebt;
import com.shangan.debt.infrastructure.DebtRepository;
import com.shangan.planning.domain.PlanItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按未满足任务组成部分生成幂等欠债并记录精确还债增量。 */
@Service
public class DefaultDebtService implements DebtService {
  private static final long QUIZ_ESTIMATE_SECONDS = 600;

  private final DebtRepository debts;
  private final IdGenerator ids;

  public DefaultDebtService(DebtRepository debts, IdGenerator ids) {
    this.debts = debts;
    this.ids = ids;
  }

  @Override
  @Transactional
  public List<LearningDebt> generate(
      String userId, LocalDate openedOn, String reason, List<PlanItem> items, Instant now) {
    for (PlanItem item : items) {
      if (item.itemType().equals("DEBT_REPAYMENT")) continue;
      long remaining = Math.max(0, item.plannedSeconds() - item.completedSeconds());
      if (item.itemType().equals("VIDEO") && !item.watchCompleted() && remaining > 0) {
        insert(
            userId, openedOn, reason, item, "VIDEO_WATCH", remaining, item.completedSeconds(), now);
      }
      if (item.itemType().equals("VIDEO") && item.quizRequired() && !item.quizCompleted()) {
        insert(userId, openedOn, reason, item, "QUIZ", QUIZ_ESTIMATE_SECONDS, 0, now);
      }
      if (item.itemType().equals("FOCUS") && remaining > 0) {
        insert(userId, openedOn, reason, item, "FOCUS", remaining, 0, now);
      }
      // 模拟考试属于复习任务，只保留执行与审计记录，不纳入学习欠债。
    }
    return new ArrayList<>(debts.findOpenByUser(userId));
  }

  @Override
  @Transactional
  public void reconcileRepayment(
      String userId, String debtId, String planItemId, long positiveDelta, Instant now) {
    if (debtId == null || positiveDelta <= 0) return;
    debts.repay(ids.nextId(), userId, debtId, planItemId, positiveDelta, "PLAN_ITEM", now);
  }

  @Override
  @Transactional
  public void reconcileOpenVideoDebt(
      String userId,
      String mediaItemId,
      long absoluteTrustedSeconds,
      boolean completed,
      Instant now) {
    for (LearningDebt debt : debts.findOpenVideoByMedia(userId, mediaItemId)) {
      long alreadyRepaid = debt.originalSeconds() - debt.remainingSeconds();
      long appliedAbsolute = debt.baselineCompletedSeconds() + alreadyRepaid;
      long delta =
          completed
              ? debt.remainingSeconds()
              : Math.max(0, absoluteTrustedSeconds - appliedAbsolute);
      if (delta > 0) {
        debts.repay(ids.nextId(), userId, debt.id(), null, delta, "DIRECT_VIDEO", now);
      }
    }
  }

  @Override
  @Transactional
  public void settleOpenQuizDebt(String userId, String mediaItemId, Instant now) {
    for (LearningDebt debt : debts.findOpenQuizByMedia(userId, mediaItemId)) {
      debts.repay(
          ids.nextId(), userId, debt.id(), null, debt.remainingSeconds(), "DIRECT_QUIZ", now);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<LearningDebt> openDebts(String userId) {
    return debts.findOpenByUser(userId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<LearningDebt> findDebt(String userId, String debtId) {
    return debtId == null ? Optional.empty() : debts.findOwned(userId, debtId);
  }

  @Override
  @Transactional(readOnly = true)
  public long openSeconds(String userId) {
    return debts.sumOpenSeconds(userId);
  }

  private void insert(
      String userId,
      LocalDate openedOn,
      String reason,
      PlanItem item,
      String type,
      long seconds,
      long baseline,
      Instant now) {
    debts.insertIfAbsent(
        new LearningDebt(
            ids.nextId(),
            userId,
            item.id(),
            type,
            item.mediaItemId(),
            item.title(),
            seconds,
            seconds,
            baseline,
            "OPEN",
            reason,
            openedOn,
            null),
        now);
  }
}
