package com.shangan.quiz.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.debt.application.DebtService;
import com.shangan.learning.infrastructure.VideoProgressRepository;
import com.shangan.planning.application.PlanProgressPort;
import com.shangan.quiz.domain.Question;
import com.shangan.quiz.infrastructure.QuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 课后答题事务边界：解锁、完整性校验、判分、落库和计划/欠债对账。 */
@Service
public class QuizService {
  private final QuestionRepository questions;
  private final VideoProgressRepository progress;
  private final PlanProgressPort plans;
  private final DebtService debts;
  private final IdGenerator ids;
  private final Clock clock;

  public QuizService(
      QuestionRepository questions,
      VideoProgressRepository progress,
      PlanProgressPort plans,
      DebtService debts,
      IdGenerator ids,
      Clock clock) {
    this.questions = questions;
    this.progress = progress;
    this.plans = plans;
    this.debts = debts;
    this.ids = ids;
    this.clock = clock;
  }

  /** 返回不含正确标记与解析的安全题面。 */
  @Transactional(readOnly = true)
  public QuizView getQuiz(String userId, String mediaItemId) {
    requireVideoCompleted(userId, mediaItemId);
    List<QuestionView> views =
        questions.findByMedia(mediaItemId, true).stream()
            .map(
                question ->
                    new QuestionView(
                        question.id(),
                        question.questionType(),
                        question.content(),
                        question.options().stream()
                            .map(option -> new OptionView(option.id(), option.content()))
                            .toList()))
            .toList();
    return new QuizView(mediaItemId, views);
  }

  /** 仅完整答卷会在同一事务内保存，并推动计划及 QUIZ 欠债。 */
  @Transactional
  public AttemptResult submit(String userId, String mediaItemId, AttemptCommand command) {
    requireVideoCompleted(userId, mediaItemId);
    if (command == null || command.durationMs() < 0 || command.answers() == null) {
      throw invalid("QUIZ_SUBMISSION_INVALID", "答题提交内容无效");
    }
    List<Question> enabled = questions.findByMedia(mediaItemId, true);
    if (enabled.isEmpty()) throw invalid("QUIZ_NOT_AVAILABLE", "该视频没有启用的课后题");
    plans.validateQuizLink(userId, command.planItemId(), mediaItemId);

    Map<String, AnswerCommand> submitted = new HashMap<>();
    for (AnswerCommand answer : command.answers()) {
      if (answer == null
          || answer.questionId() == null
          || answer.selectedOptionId() == null
          || answer.durationMs() < 0
          || submitted.putIfAbsent(answer.questionId(), answer) != null) {
        throw invalid("QUIZ_ANSWERS_INCOMPLETE", "每道启用题目必须且只能提交一个答案");
      }
    }
    Set<String> expected =
        enabled.stream().map(Question::id).collect(java.util.stream.Collectors.toSet());
    if (!submitted.keySet().equals(expected)) {
      throw invalid("QUIZ_ANSWERS_INCOMPLETE", "每道启用题目必须且只能提交一个答案");
    }

    String attemptId = ids.nextId();
    int correctCount = 0;
    java.util.ArrayList<QuestionRepository.Answer> storedAnswers = new java.util.ArrayList<>();
    java.util.ArrayList<AnswerResult> results = new java.util.ArrayList<>();
    for (Question question : enabled) {
      AnswerCommand answer = submitted.get(question.id());
      boolean correct = question.correct(answer.selectedOptionId());
      if (correct) correctCount++;
      storedAnswers.add(
          new QuestionRepository.Answer(
              ids.nextId(),
              question.id(),
              answer.selectedOptionId(),
              correct,
              answer.durationMs()));
      results.add(
          new AnswerResult(
              question.id(), answer.selectedOptionId(), correct, question.explanation()));
    }
    int score = (int) Math.round(correctCount * 100.0 / enabled.size());
    Instant now = clock.instant();
    questions.insertAttempt(
        new QuestionRepository.Attempt(
            attemptId,
            userId,
            mediaItemId,
            score,
            correctCount,
            enabled.size(),
            command.durationMs()),
        storedAnswers,
        now);
    if (command.planItemId() != null) {
      plans.markQuizCompleted(userId, command.planItemId());
    }
    debts.settleOpenQuizDebt(userId, mediaItemId, now);
    return new AttemptResult(
        attemptId, score, correctCount, enabled.size(), command.durationMs(), now, results);
  }

  @Transactional(readOnly = true)
  public List<AttemptSummary> attempts(String userId, String mediaItemId) {
    return questions.findAttempts(userId, mediaItemId).stream()
        .map(
            attempt ->
                new AttemptSummary(
                    attempt.id(),
                    attempt.score(),
                    attempt.correctCount(),
                    attempt.totalCount(),
                    attempt.durationMs(),
                    attempt.submittedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<Question> adminQuestions(String mediaItemId) {
    return questions.findByMedia(mediaItemId, false);
  }

  /** 返回课程内每个课时的正式题目数量，避免管理台轮询时逐课时查询。 */
  @Transactional(readOnly = true)
  public Map<String, Integer> adminQuestionCounts(String courseId) {
    return questions.countByCourse(courseId);
  }

  @Transactional(readOnly = true)
  public Question adminQuestion(String questionId) {
    return questions
        .findById(questionId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "题目不存在"));
  }

  /** 管理员保存题目前先在领域层验证题型和选项约束。 */
  @Transactional
  public Question saveQuestion(AdminQuestionCommand command) {
    String questionId = blank(command.questionId()) ? ids.nextId() : command.questionId();
    if (command.options() == null) throw invalid("QUIZ_QUESTION_INVALID", "题目选项不能为空");
    List<Question.Option> options =
        command.options().stream()
            .map(
                option ->
                    new Question.Option(
                        blank(option.id()) ? ids.nextId() : option.id(),
                        option.content(),
                        option.correct(),
                        option.sortOrder()))
            .toList();
    Question question =
        new Question(
            questionId,
            command.mediaItemId(),
            command.questionType(),
            command.content(),
            command.explanation() == null ? "" : command.explanation(),
            command.enabled(),
            command.sortOrder(),
            options);
    question.validate();
    questions
        .findById(questionId)
        .ifPresent(
            existing -> {
              Set<String> existingIds =
                  existing.options().stream()
                      .map(Question.Option::id)
                      .collect(java.util.stream.Collectors.toSet());
              Set<String> nextIds =
                  options.stream()
                      .map(Question.Option::id)
                      .collect(java.util.stream.Collectors.toSet());
              if (!existingIds.equals(nextIds) && questions.hasAnswers(questionId)) {
                throw invalid("QUIZ_QUESTION_HAS_ATTEMPTS", "已有答题记录的题目不能增删选项，可修改文字或启用状态");
              }
            });
    questions.saveQuestion(question, clock.instant());
    return question;
  }

  private void requireVideoCompleted(String userId, String mediaItemId) {
    boolean completed =
        progress.find(userId, mediaItemId).map(value -> value.completedAt() != null).orElse(false);
    if (!completed) {
      throw new BusinessException(HttpStatus.CONFLICT, "QUIZ_LOCKED", "视频可信观看完成后才能答题");
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
  }

  public record QuizView(String mediaItemId, List<QuestionView> questions) {}

  public record QuestionView(
      String id, String questionType, String content, List<OptionView> options) {}

  public record OptionView(String id, String content) {}

  public record AttemptCommand(String planItemId, long durationMs, List<AnswerCommand> answers) {}

  public record AnswerCommand(String questionId, String selectedOptionId, long durationMs) {}

  public record AttemptResult(
      String id,
      int score,
      int correctCount,
      int totalCount,
      long durationMs,
      Instant submittedAt,
      List<AnswerResult> answers) {}

  public record AnswerResult(
      String questionId, String selectedOptionId, boolean correct, String explanation) {}

  public record AttemptSummary(
      String id,
      int score,
      int correctCount,
      int totalCount,
      long durationMs,
      Instant submittedAt) {}

  public record AdminQuestionCommand(
      String questionId,
      String mediaItemId,
      String questionType,
      String content,
      String explanation,
      boolean enabled,
      int sortOrder,
      List<AdminOptionCommand> options) {}

  public record AdminOptionCommand(String id, String content, boolean correct, int sortOrder) {}
}
