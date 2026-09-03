package com.shangan.dashboard;

import com.shangan.common.auth.CurrentUser;
import com.shangan.debt.application.DebtService;
import com.shangan.exam.api.ExamController.ExamGoalResponse;
import com.shangan.exam.application.ExamGoalService;
import com.shangan.exam.application.ExamProgressCalculator;
import com.shangan.planning.application.DailyPlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 聚合首页考试、计划和欠债首屏信息。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  private final ExamGoalService exams;
  private final DailyPlanService plans;
  private final DebtService debts;

  public DashboardController(ExamGoalService exams, DailyPlanService plans, DebtService debts) {
    this.exams = exams;
    this.plans = plans;
    this.debts = debts;
  }

  @GetMapping
  DashboardResponse dashboard(CurrentUser currentUser) {
    java.util.List<ExamCard> examCards =
        exams.listGoals(currentUser.userId()).stream()
            .map(
                goal ->
                    new ExamCard(
                        ExamGoalResponse.from(goal),
                        exams.progress(currentUser.userId(), goal.id())))
            .toList();
    ExamCard first = examCards.isEmpty() ? null : examCards.getFirst();
    DailyPlanService.PlanSummary plan =
        plans.todaySummary(currentUser.userId(), currentUser.timezone());
    return new DashboardResponse(
        first == null ? null : first.exam(),
        new TodayPlan(plan.status(), plan.plannedSeconds(), plan.completedSeconds()),
        debts.openSeconds(currentUser.userId()),
        0,
        0,
        null,
        first == null ? null : first.progressPressure(),
        examCards);
  }

  record ExamCard(ExamGoalResponse exam, ExamProgressCalculator.Progress progressPressure) {}

  record DashboardResponse(
      ExamGoalResponse exam,
      TodayPlan todayPlan,
      long openDebtSeconds,
      long studyTodaySeconds,
      double answerAccuracy,
      Object continueLesson,
      ExamProgressCalculator.Progress progressPressure,
      java.util.List<ExamCard> exams) {}

  record TodayPlan(String status, long plannedSeconds, long completedSeconds) {}
}
