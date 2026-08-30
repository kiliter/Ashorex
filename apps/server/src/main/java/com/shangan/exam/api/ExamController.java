package com.shangan.exam.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.exam.application.ExamGoalService;
import com.shangan.exam.application.ExamProgressCalculator;
import com.shangan.exam.domain.ExamGoal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 考试目标和只读进度压力 API。 */
@RestController
@RequestMapping("/api/v1")
public class ExamController {

  private final ExamGoalService exams;

  public ExamController(ExamGoalService exams) {
    this.exams = exams;
  }

  @GetMapping("/exam-goal")
  ResponseEntity<ExamGoalResponse> goal(CurrentUser currentUser) {
    return exams
        .findGoal(currentUser.userId())
        .map(ExamGoalResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PutMapping("/exam-goal")
  ExamGoalResponse save(CurrentUser currentUser, @Valid @RequestBody ExamGoalRequest request) {
    return ExamGoalResponse.from(
        exams.saveGoal(
            currentUser.userId(),
            currentUser.timezone(),
            request.name(),
            request.examDate(),
            request.targetCompletionDate(),
            request.reviewBufferDays(),
            request.courseIds()));
  }

  @GetMapping("/exam-progress")
  ExamProgressCalculator.Progress progress(CurrentUser currentUser) {
    return exams.progress(currentUser.userId());
  }

  record ExamGoalRequest(
      @NotBlank String name,
      @NotNull LocalDate examDate,
      @NotNull LocalDate targetCompletionDate,
      @Min(0) @Max(365) int reviewBufferDays,
      @NotEmpty List<@NotBlank String> courseIds) {}

  public record ExamGoalResponse(
      String id,
      String name,
      LocalDate examDate,
      LocalDate targetCompletionDate,
      int reviewBufferDays,
      String timezone,
      List<String> courseIds) {
    public static ExamGoalResponse from(ExamGoal goal) {
      return new ExamGoalResponse(
          goal.id(),
          goal.name(),
          goal.examDate(),
          goal.targetCompletionDate(),
          goal.reviewBufferDays(),
          goal.timezone(),
          goal.courseIds());
    }
  }
}
