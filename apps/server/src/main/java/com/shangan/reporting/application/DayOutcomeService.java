package com.shangan.reporting.application;

import com.shangan.common.IdGenerator;
import com.shangan.planning.application.DailyPlanService;
import com.shangan.reporting.infrastructure.DayOutcomeRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按用户自然日补算日终结果，最后结果由服务端原始记录确定。 */
@Service
public class DayOutcomeService {
  private static final int MAX_CATCH_UP_DAYS_PER_RUN = 31;

  private final DayOutcomeRepository outcomes;
  private final DailyPlanService plans;
  private final IdGenerator ids;
  private final Clock clock;

  public DayOutcomeService(
      DayOutcomeRepository outcomes, DailyPlanService plans, IdGenerator ids, Clock clock) {
    this.outcomes = outcomes;
    this.plans = plans;
    this.ids = ids;
    this.clock = clock;
  }

  public void settleDueDays() {
    for (var user : outcomes.users()) {
      ZoneId zone = ZoneId.of(user.timezone());
      var localNow = clock.instant().atZone(zone);
      LocalDate latestDue =
          localNow.toLocalTime().isBefore(LocalTime.parse(user.dayEndLocalTime()))
              ? localNow.toLocalDate().minusDays(1)
              : localNow.toLocalDate();
      LocalDate next =
          outcomes.latestOutcomeDate(user.userId()).map(date -> date.plusDays(1)).orElse(latestDue);
      int processed = 0;
      while (!next.isAfter(latestDue) && processed < MAX_CATCH_UP_DAYS_PER_RUN) {
        settle(user.userId(), next, zone);
        next = next.plusDays(1);
        processed++;
      }
    }
  }

  @Transactional
  public String settle(String userId, LocalDate date, ZoneId zone) {
    var plan = outcomes.findPlan(userId, date);
    if (plan.isPresent()
        && plan.get().accountableItems() > 0
        && plan.get().lifecycleStatus().equals("ACTIVE")) {
      plans.closeForDayEnd(userId, plan.get().planId());
      plan = outcomes.findPlan(userId, date);
    }

    String outcome;
    if (plan.isPresent() && plan.get().accountableItems() > 0) {
      outcome = plan.get().lifecycleStatus().equals("COMPLETED") ? "COMPLETED" : "CLOSED_WITH_DEBT";
    } else {
      var start = date.atStartOfDay(zone).toInstant();
      var end = date.plusDays(1).atStartOfDay(zone).toInstant();
      outcome = outcomes.hasEffectiveActivity(userId, start, end) ? "FREE_STUDY" : "SLACKED";
    }
    outcomes.upsert(ids.nextId(), userId, date, outcome, clock.instant());
    return outcome;
  }
}
