package com.shangan.quiz.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.quiz.application.QuizService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** iOS 课后题读取、完整答卷提交和历史尝试 API。 */
@RestController
@RequestMapping("/api/v1/lessons/{lessonId}")
public class QuizController {
  private final QuizService quizzes;

  public QuizController(QuizService quizzes) {
    this.quizzes = quizzes;
  }

  @GetMapping("/quiz")
  QuizService.QuizView quiz(CurrentUser user, @PathVariable String lessonId) {
    return quizzes.getQuiz(user.userId(), lessonId);
  }

  @PostMapping("/quiz-attempts")
  QuizService.AttemptResult submit(
      CurrentUser user, @PathVariable String lessonId, @Valid @RequestBody AttemptRequest request) {
    return quizzes.submit(user.userId(), lessonId, request.toCommand());
  }

  @GetMapping("/quiz-attempts")
  List<QuizService.AttemptSummary> attempts(CurrentUser user, @PathVariable String lessonId) {
    return quizzes.attempts(user.userId(), lessonId);
  }

  record AttemptRequest(
      String planItemId, @Min(0) long durationMs, @NotEmpty List<@Valid AnswerRequest> answers) {
    QuizService.AttemptCommand toCommand() {
      return new QuizService.AttemptCommand(
          planItemId, durationMs, answers.stream().map(AnswerRequest::toCommand).toList());
    }
  }

  record AnswerRequest(
      @NotBlank String questionId, @NotBlank String selectedOptionId, @Min(0) long durationMs) {
    QuizService.AnswerCommand toCommand() {
      return new QuizService.AnswerCommand(questionId, selectedOptionId, durationMs);
    }
  }
}
