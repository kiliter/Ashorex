package com.shangan.goal.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.goal.application.ExamGoalService;
import com.shangan.goal.application.ExamGoalService.GoalView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 考试目标 API；目标只做倒计时看板，不绑定课程。 */
@RestController
@RequestMapping("/api/v1/exam-goals")
public class ExamGoalController {

  private final ExamGoalService goals;

  public ExamGoalController(ExamGoalService goals) {
    this.goals = goals;
  }

  @GetMapping
  List<GoalView> list(CurrentUser currentUser) {
    return goals.list(currentUser.userId());
  }

  @PostMapping
  GoalView create(CurrentUser currentUser, @Valid @RequestBody CreateGoalRequest request) {
    return goals.create(
        currentUser.userId(),
        request.name(),
        request.examDate(),
        request.note(),
        Boolean.TRUE.equals(request.primary()));
  }

  @PatchMapping("/{goalId}")
  GoalView update(
      CurrentUser currentUser,
      @PathVariable String goalId,
      @RequestBody UpdateGoalRequest request) {
    return goals.update(
        currentUser.userId(),
        goalId,
        request.name(),
        request.examDate(),
        request.note(),
        request.primary());
  }

  @DeleteMapping("/{goalId}")
  ResponseEntity<Void> delete(CurrentUser currentUser, @PathVariable String goalId) {
    goals.delete(currentUser.userId(), goalId);
    return ResponseEntity.noContent().build();
  }

  record CreateGoalRequest(
      @NotBlank String name, @NotBlank String examDate, String note, Boolean primary) {}

  record UpdateGoalRequest(String name, String examDate, String note, Boolean primary) {}
}
