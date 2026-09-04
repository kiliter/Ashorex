package com.shangan.debt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.shangan.debt.application.DefaultDebtService;
import com.shangan.debt.domain.LearningDebt;
import com.shangan.debt.infrastructure.DebtRepository;
import com.shangan.planning.domain.PlanItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 覆盖欠债生成的类型拆分、幂等键和可配置答题估值，以及直接学习的精确对账。 */
class DefaultDebtServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-04T15:00:00Z");
  private static final LocalDate TODAY = LocalDate.parse("2026-09-04");

  @Test
  void videoTaskCreatesSeparateWatchAndQuizDebts() {
    FakeRepository debts = new FakeRepository();
    DefaultDebtService service = service(debts, 600);

    service.generate(
        "user-1",
        TODAY,
        "DAY_END",
        List.of(videoItem("item-1", 1200, 300, false, true, false)),
        NOW);

    assertThat(debts.inserted)
        .extracting(LearningDebt::debtType, LearningDebt::remainingSeconds)
        .containsExactly(tuple("VIDEO_WATCH", 900L), tuple("QUIZ", 600L));
    assertThat(debts.inserted.getFirst().baselineCompletedSeconds()).isEqualTo(300);
  }

  @Test
  void quizEstimateComesFromConfiguration() {
    FakeRepository debts = new FakeRepository();
    DefaultDebtService service = service(debts, 900);

    service.generate(
        "user-1", TODAY, "DAY_END", List.of(videoItem("item-1", 600, 600, true, true, false)), NOW);

    assertThat(service.quizEstimateSeconds()).isEqualTo(900);
    assertThat(debts.inserted)
        .extracting(LearningDebt::debtType, LearningDebt::remainingSeconds)
        .containsExactly(tuple("QUIZ", 900L));
  }

  @Test
  void repaymentTaskAndSatisfiedTaskNeverCreateDebt() {
    FakeRepository debts = new FakeRepository();
    DefaultDebtService service = service(debts, 600);

    service.generate(
        "user-1",
        TODAY,
        "DAY_END",
        List.of(
            new PlanItem(
                "item-1",
                "plan-1",
                "DEBT_REPAYMENT",
                "还债：第一课",
                "media-1",
                "debt-1",
                600,
                0,
                false,
                false,
                false,
                "PENDING",
                0,
                null),
            videoItem("item-2", 600, 600, true, false, false),
            focusItem("item-3", 1500, 1500)),
        NOW);

    assertThat(debts.inserted).isEmpty();
  }

  @Test
  void repeatedGenerationKeepsOneRowPerTaskAndType() {
    FakeRepository debts = new FakeRepository();
    DefaultDebtService service = service(debts, 600);
    List<PlanItem> items = List.of(videoItem("item-1", 1200, 0, false, true, false));

    service.generate("user-1", TODAY, "DAY_END", items, NOW);
    service.generate("user-1", TODAY, "DAY_END", items, NOW);

    assertThat(debts.stored.keySet()).containsExactly("item-1|VIDEO_WATCH", "item-1|QUIZ");
    assertThat(debts.inserted).hasSize(2);
  }

  @Test
  void directWatchRepaysOnlyProgressBeyondBaselineAndClearsOnCompletion() {
    FakeRepository debts = new FakeRepository();
    DefaultDebtService service = service(debts, 600);
    debts.putOpen(openWatchDebt(900, 900, 300));

    service.reconcileOpenVideoDebt("user-1", "media-1", 500, false, NOW);
    assertThat(debts.repayments).containsExactly(tuple("debt-1", 200L, "DIRECT_VIDEO"));

    service.reconcileOpenVideoDebt("user-1", "media-1", 400, false, NOW);
    assertThat(debts.repayments).hasSize(1);

    service.reconcileOpenVideoDebt("user-1", "media-1", 400, true, NOW);
    assertThat(debts.repayments)
        .containsExactly(
            tuple("debt-1", 200L, "DIRECT_VIDEO"), tuple("debt-1", 700L, "DIRECT_VIDEO"));
  }

  @Test
  void nonPositiveQuizEstimateIsRejectedAtStartup() {
    assertThatThrownBy(() -> service(new FakeRepository(), 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("QUIZ_DEBT_ESTIMATE_SECONDS");
  }

  private DefaultDebtService service(FakeRepository debts, long quizEstimateSeconds) {
    return new DefaultDebtService(debts, new SequentialIds(), quizEstimateSeconds);
  }

  private PlanItem videoItem(
      String id,
      long planned,
      long completed,
      boolean watchCompleted,
      boolean quizRequired,
      boolean quizCompleted) {
    return new PlanItem(
        id,
        "plan-1",
        "VIDEO",
        "第一课",
        "media-1",
        null,
        planned,
        completed,
        watchCompleted,
        quizRequired,
        quizCompleted,
        "PENDING",
        0,
        null);
  }

  private PlanItem focusItem(String id, long planned, long completed) {
    return new PlanItem(
        id, "plan-1", "FOCUS", "专注", null, null, planned, completed, false, false, false, "PENDING",
        1, null);
  }

  private LearningDebt openWatchDebt(long original, long remaining, long baseline) {
    return new LearningDebt(
        "debt-1",
        "user-1",
        "item-1",
        "VIDEO_WATCH",
        "media-1",
        "第一课",
        original,
        remaining,
        baseline,
        "OPEN",
        "DAY_END",
        TODAY,
        null);
  }

  /** 顺序 ID 生成器让断言可以直接比较稳定的欠债和偿还标识。 */
  private static final class SequentialIds implements com.shangan.common.IdGenerator {
    private int counter;

    @Override
    public String nextId() {
      return "id-" + (++counter);
    }
  }

  /** 以 (source_plan_item_id, debt_type) 作为唯一键复现数据库幂等约束。 */
  private static final class FakeRepository implements DebtRepository {
    private final Map<String, LearningDebt> stored = new LinkedHashMap<>();
    private final List<LearningDebt> inserted = new ArrayList<>();
    private final List<org.assertj.core.groups.Tuple> repayments = new ArrayList<>();

    void putOpen(LearningDebt debt) {
      stored.put(key(debt), debt);
    }

    private String key(LearningDebt debt) {
      return debt.sourcePlanItemId() + "|" + debt.debtType();
    }

    @Override
    public List<LearningDebt> findOpenByUser(String userId) {
      return stored.values().stream()
          .filter(debt -> debt.userId().equals(userId) && !debt.status().equals("PAID"))
          .toList();
    }

    @Override
    public Optional<LearningDebt> findOwned(String userId, String debtId) {
      return stored.values().stream()
          .filter(debt -> debt.userId().equals(userId) && debt.id().equals(debtId))
          .findFirst();
    }

    @Override
    public List<LearningDebt> findOpenVideoByMedia(String userId, String mediaItemId) {
      return findOpenByMedia(userId, mediaItemId, "VIDEO_WATCH");
    }

    @Override
    public List<LearningDebt> findOpenQuizByMedia(String userId, String mediaItemId) {
      return findOpenByMedia(userId, mediaItemId, "QUIZ");
    }

    private List<LearningDebt> findOpenByMedia(String userId, String mediaItemId, String type) {
      return stored.values().stream()
          .filter(debt -> debt.userId().equals(userId))
          .filter(debt -> mediaItemId.equals(debt.mediaItemId()))
          .filter(debt -> debt.debtType().equals(type))
          .filter(debt -> !debt.status().equals("PAID"))
          .toList();
    }

    @Override
    public void insertIfAbsent(LearningDebt debt, Instant now) {
      if (stored.putIfAbsent(key(debt), debt) == null) inserted.add(debt);
    }

    @Override
    public long sumOpenSeconds(String userId) {
      return findOpenByUser(userId).stream().mapToLong(LearningDebt::remainingSeconds).sum();
    }

    @Override
    public long repay(
        String repaymentId,
        String userId,
        String debtId,
        String planItemId,
        long seconds,
        String source,
        Instant now) {
      LearningDebt debt = findOwned(userId, debtId).orElseThrow();
      long applied = Math.min(seconds, debt.remainingSeconds());
      long remaining = debt.remainingSeconds() - applied;
      stored.put(
          key(debt),
          new LearningDebt(
              debt.id(),
              debt.userId(),
              debt.sourcePlanItemId(),
              debt.debtType(),
              debt.mediaItemId(),
              debt.title(),
              debt.originalSeconds(),
              remaining,
              debt.baselineCompletedSeconds(),
              remaining == 0 ? "PAID" : "PARTIALLY_REPAID",
              debt.reason(),
              debt.openedOn(),
              remaining == 0 ? now : null));
      repayments.add(tuple(debtId, applied, source));
      return applied;
    }
  }
}
