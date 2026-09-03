package com.shangan.exam.application;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.exam.domain.ExamGoal;
import com.shangan.exam.infrastructure.ExamGoalRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理用户考试目标，并编排课程范围内的只读进度计算。 */
@Service
public class ExamGoalService {

  private final ExamGoalRepository goals;
  private final CatalogQueryService catalog;
  private final ExamLearningProgressPort learningProgress;
  private final ExamProgressCalculator calculator;
  private final IdGenerator ids;
  private final Clock clock;

  public ExamGoalService(
      ExamGoalRepository goals,
      CatalogQueryService catalog,
      ExamLearningProgressPort learningProgress,
      ExamProgressCalculator calculator,
      IdGenerator ids,
      Clock clock) {
    this.goals = goals;
    this.catalog = catalog;
    this.learningProgress = learningProgress;
    this.calculator = calculator;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Optional<ExamGoal> findGoal(String userId) {
    return goals.findByUserId(userId);
  }

  @Transactional(readOnly = true)
  public List<ExamGoal> listGoals(String userId) {
    return goals.listByUserId(userId);
  }

  @Transactional(readOnly = true)
  public Optional<ExamGoal> findGoal(String userId, String goalId) {
    return goals.findById(userId, goalId);
  }

  /** 新建考试目标；不再覆盖该用户已有的其它考试。 */
  @Transactional
  public ExamGoal saveGoal(
      String userId,
      String timezone,
      String name,
      LocalDate examDate,
      LocalDate targetCompletionDate,
      int reviewBufferDays,
      List<String> requestedCourseIds) {
    return persist(
        userId,
        timezone,
        ids.nextId(),
        name,
        examDate,
        targetCompletionDate,
        reviewBufferDays,
        requestedCourseIds);
  }

  /** 更新当前用户拥有的指定考试目标。 */
  @Transactional
  public ExamGoal updateGoal(
      String userId,
      String goalId,
      String timezone,
      String name,
      LocalDate examDate,
      LocalDate targetCompletionDate,
      int reviewBufferDays,
      List<String> requestedCourseIds) {
    goals
        .findById(userId, goalId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "EXAM_GOAL_NOT_FOUND", "考试目标不存在"));
    return persist(
        userId,
        timezone,
        goalId,
        name,
        examDate,
        targetCompletionDate,
        reviewBufferDays,
        requestedCourseIds);
  }

  private ExamGoal persist(
      String userId,
      String timezone,
      String goalId,
      String name,
      LocalDate examDate,
      LocalDate targetCompletionDate,
      int reviewBufferDays,
      List<String> requestedCourseIds) {
    validateDates(examDate, targetCompletionDate, reviewBufferDays);
    List<String> courseIds = List.copyOf(new LinkedHashSet<>(requestedCourseIds));
    if (courseIds.isEmpty()) {
      throw invalid("至少选择一门参与进度计算的课程");
    }
    for (String courseId : courseIds) {
      if (catalog.findCourse(courseId).isEmpty()) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "EXAM_COURSE_INVALID", "所选课程不可用");
      }
    }
    ZoneId.of(timezone);
    ExamGoal goal =
        new ExamGoal(
            goalId,
            userId,
            name.trim(),
            examDate,
            targetCompletionDate,
            reviewBufferDays,
            timezone,
            courseIds);
    goals.save(goal, clock.instant());
    return goal;
  }

  @Transactional(readOnly = true)
  public ExamProgressCalculator.Progress progress(String userId) {
    return progress(userId, null);
  }

  @Transactional(readOnly = true)
  public ExamProgressCalculator.Progress progress(String userId, String goalId) {
    ExamGoal goal =
        (goalId == null ? goals.findByUserId(userId) : goals.findById(userId, goalId))
            .orElseThrow(
                () ->
                    new BusinessException(HttpStatus.NOT_FOUND, "EXAM_GOAL_NOT_FOUND", "尚未设置考试目标"));
    int totalLessons =
        goal.courseIds().stream().mapToInt(id -> catalog.listEnabledLessons(id).size()).sum();
    ExamLearningProgressPort.Completion completion =
        learningProgress.completionFor(userId, goal.courseIds());
    return calculator.calculate(
        goal.examDate(),
        goal.targetCompletionDate(),
        ZoneId.of(goal.timezone()),
        totalLessons,
        completion.completedLessons(),
        completion.completedInLastSevenDays());
  }

  private void validateDates(
      LocalDate examDate, LocalDate targetCompletionDate, int reviewBufferDays) {
    if (targetCompletionDate.isAfter(examDate)) {
      throw invalid("计划完成课程日期不能晚于考试日期");
    }
    if (reviewBufferDays < 0 || reviewBufferDays > 365) {
      throw invalid("复习缓冲天数必须在 0 到 365 之间");
    }
  }

  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "EXAM_GOAL_INVALID", message);
  }
}
