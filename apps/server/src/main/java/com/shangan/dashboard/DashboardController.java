package com.shangan.dashboard;

import com.shangan.common.auth.CurrentUser;
import com.shangan.exam.api.ExamController.ExamGoalResponse;
import com.shangan.exam.application.ExamGoalService;
import com.shangan.exam.application.ExamProgressCalculator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 聚合首页首屏所需只读信息，未落地模块先返回稳定零值基线。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  private final ExamGoalService exams;

  public DashboardController(ExamGoalService exams) {
    this.exams = exams;
  }

  @GetMapping
  DashboardResponse dashboard(CurrentUser currentUser) {
    ExamGoalResponse goal =
        exams.findGoal(currentUser.userId()).map(ExamGoalResponse::from).orElse(null);
    ExamProgressCalculator.Progress progress =
        goal == null ? null : exams.progress(currentUser.userId());
    return new DashboardResponse(goal, new TodayPlan("NONE", 0, 0), 0, 0, 0, null, progress);
  }

  record DashboardResponse(
      ExamGoalResponse exam,
      TodayPlan todayPlan,
      long openDebtSeconds,
      long studyTodaySeconds,
      double answerAccuracy,
      Object continueLesson,
      ExamProgressCalculator.Progress progressPressure) {}

  record TodayPlan(String status, long plannedSeconds, long completedSeconds) {}
}
