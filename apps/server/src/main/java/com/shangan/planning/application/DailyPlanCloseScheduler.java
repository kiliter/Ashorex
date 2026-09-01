package com.shangan.planning.application;

import com.shangan.reporting.application.DayOutcomeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟按用户 IANA 时区关闭已到日终的锁定计划。 */
@Component
public class DailyPlanCloseScheduler {
  private final DailyPlanService plans;
  private final DayOutcomeService outcomes;

  public DailyPlanCloseScheduler(DailyPlanService plans, DayOutcomeService outcomes) {
    this.plans = plans;
    this.outcomes = outcomes;
  }

  @Scheduled(fixedDelay = 60_000L)
  public void closeDuePlans() {
    plans.lockedPlans().stream()
        .filter(plans::isDue)
        .forEach(plan -> plans.closeForDayEnd(plan.userId(), plan.planId()));
    outcomes.settleDueDays();
  }
}
