package com.shangan.planning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.planning.domain.DailyPlan;
import com.shangan.planning.domain.PlanStatus;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 覆盖每日计划状态机允许与禁止的关键迁移。 */
class DailyPlanStateMachineTest {

  @Test
  void lockedPlanRejectsMutation() {
    DailyPlan plan = DailyPlan.draft("plan-1", "user-1", LocalDate.of(2026, 8, 30));
    plan.addItem("item-1");
    plan.lock(Instant.parse("2026-08-30T01:00:00Z"));

    assertThatThrownBy(() -> plan.addItem("item-2"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("计划已锁定");
    assertThatThrownBy(() -> plan.removeItem("item-1"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("计划已锁定");
  }

  @Test
  void abandonmentIsFinalAndOnlyLockedPlanCanClose() {
    DailyPlan plan = DailyPlan.draft("plan-1", "user-1", LocalDate.of(2026, 8, 30));
    assertThatThrownBy(() -> plan.abandon(Instant.EPOCH)).isInstanceOf(BusinessException.class);

    plan.addItem("item-1");
    plan.lock(Instant.EPOCH);
    plan.abandon(Instant.EPOCH.plusSeconds(1));

    org.assertj.core.api.Assertions.assertThat(plan.status()).isEqualTo(PlanStatus.ABANDONED);
    assertThatThrownBy(() -> plan.complete(Instant.EPOCH.plusSeconds(2)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void emptyPlanCannotLock() {
    DailyPlan plan = DailyPlan.draft("plan-1", "user-1", LocalDate.of(2026, 8, 30));
    assertThatThrownBy(() -> plan.lock(Instant.EPOCH))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("至少添加一个任务");
  }
}
