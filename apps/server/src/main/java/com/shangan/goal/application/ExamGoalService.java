package com.shangan.goal.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.goal.domain.ExamGoal;
import com.shangan.goal.domain.GoalUrgency;
import com.shangan.goal.infrastructure.ExamGoalRepository;
import com.shangan.identity.application.UserTimeService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 考试目标的读写与主目标唯一性维护；剩余天数一律由服务端计算。 */
@Service
public class ExamGoalService {

  private static final int MAX_GOALS_PER_USER = 12;

  private final ExamGoalRepository goals;
  private final UserTimeService userTime;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public ExamGoalService(
      ExamGoalRepository goals, UserTimeService userTime, IdGenerator idGenerator, Clock clock) {
    this.goals = goals;
    this.userTime = userTime;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  /** 目标看板：主目标在前，其余按考试日期升序，全部返回给客户端一次渲染。 */
  @Transactional(readOnly = true)
  public List<GoalView> list(String userId) {
    LocalDate today = userTime.today(userId);
    List<GoalView> views = new ArrayList<>();
    for (ExamGoal goal : goals.findByUser(userId)) {
      views.add(GoalView.of(goal, today));
    }
    return List.copyOf(views);
  }

  @Transactional
  public GoalView create(
      String userId, String name, String examDate, String note, boolean primary) {
    List<ExamGoal> existing = goals.findByUser(userId);
    if (existing.size() >= MAX_GOALS_PER_USER) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "GOAL_LIMIT_REACHED", "目标数量已达上限 " + MAX_GOALS_PER_USER);
    }
    String normalizedName = requireName(name);
    LocalDate parsedDate = parseDate(examDate);
    boolean shouldBePrimary = primary || existing.isEmpty();
    ExamGoal goal =
        new ExamGoal(
            idGenerator.nextId(),
            userId,
            normalizedName,
            parsedDate,
            note == null ? "" : note.trim(),
            shouldBePrimary);
    if (shouldBePrimary) {
      goals.clearPrimary(userId, goal.id(), clock.instant());
    }
    goals.insert(goal, clock.instant());
    return GoalView.of(goal, userTime.today(userId));
  }

  @Transactional
  public GoalView update(
      String userId, String goalId, String name, String examDate, String note, Boolean primary) {
    ExamGoal existing = requireOwnedGoal(userId, goalId);
    boolean shouldBePrimary = primary == null ? existing.primary() : primary;
    ExamGoal updated =
        new ExamGoal(
            existing.id(),
            existing.userId(),
            name == null || name.isBlank() ? existing.name() : requireName(name),
            examDate == null || examDate.isBlank() ? existing.examDate() : parseDate(examDate),
            note == null ? existing.note() : note.trim(),
            shouldBePrimary);
    if (shouldBePrimary) {
      goals.clearPrimary(userId, updated.id(), clock.instant());
    }
    goals.update(updated, clock.instant());
    return GoalView.of(updated, userTime.today(userId));
  }

  /** 删除目标；若删掉的是主目标，则把剩余最近一个目标提升为主目标。 */
  @Transactional
  public void delete(String userId, String goalId) {
    ExamGoal existing = requireOwnedGoal(userId, goalId);
    goals.delete(existing.id());
    if (!existing.primary()) {
      return;
    }
    List<ExamGoal> remaining = goals.findByUser(userId);
    if (remaining.isEmpty()) {
      return;
    }
    ExamGoal next = remaining.getFirst();
    goals.update(
        new ExamGoal(next.id(), next.userId(), next.name(), next.examDate(), next.note(), true),
        clock.instant());
  }

  private ExamGoal requireOwnedGoal(String userId, String goalId) {
    ExamGoal goal =
        goals
            .findById(goalId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", "目标不存在"));
    if (!goal.userId().equals(userId)) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", "目标不存在");
    }
    return goal;
  }

  private String requireName(String name) {
    String normalized = name == null ? "" : name.trim();
    if (normalized.isEmpty() || normalized.length() > 40) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "GOAL_NAME_INVALID", "目标名称必须为 1 到 40 个字符");
    }
    return normalized;
  }

  private LocalDate parseDate(String examDate) {
    try {
      return LocalDate.parse(examDate);
    } catch (DateTimeParseException | NullPointerException exception) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "GOAL_DATE_INVALID", "考试日期格式必须为 YYYY-MM-DD");
    }
  }

  /** 目标看板视图：剩余天数与紧急度由服务端计算，客户端不自行推算。 */
  public record GoalView(
      String id,
      String name,
      LocalDate examDate,
      String note,
      boolean primary,
      long daysRemaining,
      GoalUrgency urgency) {

    static GoalView of(ExamGoal goal, LocalDate today) {
      return new GoalView(
          goal.id(),
          goal.name(),
          goal.examDate(),
          goal.note(),
          goal.primary(),
          goal.daysRemaining(today),
          goal.urgency(today));
    }
  }
}
