package com.shangan.supervision.application;

import com.shangan.goal.application.ExamGoalService;
import com.shangan.goal.domain.GoalUrgency;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.application.PresenceService;
import com.shangan.presence.application.PresenceService.PresenceView;
import com.shangan.presence.domain.PresenceState;
import com.shangan.stats.application.StatsService;
import com.shangan.stats.application.StatsService.StatsView;
import com.shangan.supervision.domain.Supervision;
import com.shangan.supervision.domain.SupervisionPermission;
import com.shangan.todo.application.TodoViewService;
import com.shangan.todo.application.TodoViewService.DayView;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 督学端只读视图。
 *
 * <p>所有方法先过 {@link SupervisionGuard}；返回的都是只读镜像，不含任何写入路径（见 ADR-0028）。
 */
@Service
public class SupervisorViewService {

  private static final int MONTHLY_DELETION_ALERT = 5;

  private final SupervisionService supervisions;
  private final SupervisionGuard guard;
  private final UserTimeService userTime;
  private final TodoViewService todoViews;
  private final TodoRepository todos;
  private final PresenceService presence;
  private final StatsService stats;
  private final NagRepository nags;
  private final ExamGoalService goals;

  public SupervisorViewService(
      SupervisionService supervisions,
      SupervisionGuard guard,
      UserTimeService userTime,
      TodoViewService todoViews,
      TodoRepository todos,
      PresenceService presence,
      StatsService stats,
      NagRepository nags,
      ExamGoalService goals) {
    this.supervisions = supervisions;
    this.guard = guard;
    this.userTime = userTime;
    this.todoViews = todoViews;
    this.todos = todos;
    this.presence = presence;
    this.stats = stats;
    this.nags = nags;
    this.goals = goals;
  }

  /** 学员总览：在线状态、今日完成比、异常徽标。 */
  @Transactional(readOnly = true)
  public List<LearnerOverview> learners(String supervisorUserId) {
    List<LearnerOverview> result = new ArrayList<>();
    for (Supervision supervision : supervisions.learnersOf(supervisorUserId)) {
      if (!supervision.canView()) {
        continue;
      }
      User learner = userTime.requireUser(supervision.learnerUserId());
      LocalDate today = userTime.today(learner);
      DayView day = todoViews.day(learner.id(), today.toString());
      PresenceView presenceView = presence.view(learner);
      int monthlyDeletions = monthlyDeletions(learner, today);
      int unanswered = unansweredNags(learner.id());
      ExamGoalService.GoalView primaryGoal = primaryGoal(learner.id());
      result.add(
          new LearnerOverview(
              learner.id(),
              learner.username(),
              learner.displayName(),
              presenceView.state(),
              presenceView.lastHeartbeatAt(),
              presenceView.idleMinutes(),
              day.totals().total(),
              day.totals().done(),
              day.totals().watchedMs(),
              day.totals().focusedMs(),
              monthlyDeletions,
              unanswered,
              alerts(day, presenceView, monthlyDeletions, unanswered),
              supervision.canNag(),
              primaryGoal == null ? null : primaryGoal.name(),
              primaryGoal == null ? null : primaryGoal.daysRemaining(),
              primaryGoal == null ? null : primaryGoal.urgency(),
              presenceView.activity()));
    }
    return List.copyOf(result);
  }

  /** 学员详情：今日 Todo 只读镜像 + 删除记录 + 指定范围统计。 */
  @Transactional(readOnly = true)
  public LearnerDetail learnerDetail(
      String supervisorUserId, String learnerUserId, String range, String date) {
    guard.requirePermission(supervisorUserId, learnerUserId, SupervisionPermission.VIEW);
    User learner = userTime.requireUser(learnerUserId);
    LocalDate today = userTime.today(learner);
    DayView day = todoViews.day(learnerUserId, date == null ? today.toString() : date);
    StatsView statsView = stats.stats(learnerUserId, range, date);
    Instant from = userTime.startOfDay(learner, day.date());
    Instant to = userTime.endOfDayExclusive(learner, day.date());
    return new LearnerDetail(
        learner.id(),
        learner.username(),
        learner.displayName(),
        presence.view(learner),
        day,
        todos.findDeletions(learnerUserId, from, to),
        statsView);
  }

  /** 督学端时间线：自动催办、我发的催办与学员删除通知混排。 */
  @Transactional(readOnly = true)
  public List<FeedItem> feed(String supervisorUserId, int limit) {
    List<String> learnerIds =
        supervisions.learnersOf(supervisorUserId).stream()
            .filter(Supervision::canView)
            .map(Supervision::learnerUserId)
            .toList();
    List<FeedItem> items = new ArrayList<>();
    for (Nag nag : nags.findByUsers(learnerIds, limit)) {
      items.add(
          new FeedItem(
              FeedKind.NAG,
              nag.userId(),
              nag.createdAt(),
              nag.message(),
              nag.trigger().name(),
              nag.status().name(),
              nag.reasonText(),
              supervisorUserId.equals(nag.triggeredByUserId())));
    }
    for (String learnerId : learnerIds) {
      User learner = userTime.requireUser(learnerId);
      LocalDate today = userTime.today(learner);
      Instant from = userTime.startOfDay(learner, today.minusDays(30));
      Instant to = userTime.endOfDayExclusive(learner, today);
      for (TodoRepository.Deletion deletion : todos.findDeletions(learnerId, from, to)) {
        items.add(
            new FeedItem(
                FeedKind.DELETION,
                learnerId,
                deletion.deletedAt(),
                "删除了「" + deletion.titleSnapshot() + "」",
                deletion.reasonTag().name(),
                "DELETED",
                deletion.reasonText(),
                false));
      }
    }
    items.sort(java.util.Comparator.comparing(FeedItem::occurredAt).reversed());
    return items.size() > limit ? List.copyOf(items.subList(0, limit)) : List.copyOf(items);
  }

  /** 学员周 / 月对比报告。 */
  @Transactional(readOnly = true)
  public List<LearnerReport> report(String supervisorUserId, String range, String date) {
    List<LearnerReport> reports = new ArrayList<>();
    for (Supervision supervision : supervisions.learnersOf(supervisorUserId)) {
      if (!supervision.canView()) {
        continue;
      }
      User learner = userTime.requireUser(supervision.learnerUserId());
      StatsView statsView = stats.stats(learner.id(), range, date);
      // 催办与学习统计使用同一日期范围，不能取最近 100 条充当日/周/月总数。
      NagRepository.ResponseCounts counts =
          nags.countResponsesBetween(learner.id(), statsView.start(), statsView.end());
      reports.add(
          new LearnerReport(
              learner.id(),
              learner.username(),
              learner.displayName(),
              statsView,
              counts.total(),
              counts.responded()));
    }
    return List.copyOf(reports);
  }

  /**
   * 取学员的主目标；没有任何目标时返回 {@code null}。
   *
   * <p>剩余天数一律复用 {@link ExamGoalService} 按学员时区算出的结果，督学端不自己算日期， 避免督学与学员时区不同导致两端「剩 82 天」对不上。
   */
  private ExamGoalService.GoalView primaryGoal(String learnerUserId) {
    return goals.list(learnerUserId).stream()
        .filter(ExamGoalService.GoalView::primary)
        .findFirst()
        .orElse(null);
  }

  private int monthlyDeletions(User learner, LocalDate today) {
    Instant from = userTime.startOfDay(learner, today.withDayOfMonth(1));
    Instant to = userTime.endOfDayExclusive(learner, today);
    return todos.findDeletions(learner.id(), from, to).size();
  }

  private int unansweredNags(String learnerUserId) {
    return (int) nags.findByUser(learnerUserId, 50).stream().filter(Nag::awaitingResponse).count();
  }

  private List<String> alerts(
      DayView day, PresenceView presenceView, int monthlyDeletions, int unanswered) {
    List<String> alerts = new ArrayList<>();
    if (day.totals().total() > 0 && day.totals().done() == 0) {
      alerts.add("ZERO_DONE");
    }
    if (presenceView.state() == PresenceState.OFFLINE) {
      alerts.add("OFFLINE");
    }
    if (monthlyDeletions >= MONTHLY_DELETION_ALERT) {
      alerts.add("FREQUENT_DELETION");
    }
    if (unanswered > 0) {
      alerts.add("NAG_UNANSWERED");
    }
    return List.copyOf(alerts);
  }

  /** 时间线条目类型。 */
  public enum FeedKind {
    NAG,
    DELETION
  }

  /**
   * 学员总览行。
   *
   * <p>{@code primaryGoalName} / {@code primaryGoalDaysRemaining} / {@code primaryGoalUrgency}
   * 三者同生共死：学员没有考试目标时全为 {@code null}，客户端据此隐藏「目标：X · 剩 N 天」一行， 不要渲染成「剩 0 天」。
   */
  public record LearnerOverview(
      String userId,
      String username,
      String displayName,
      PresenceState presenceState,
      Instant lastHeartbeatAt,
      long idleMinutes,
      int todayTotal,
      int todayDone,
      long todayWatchedMs,
      long todayFocusedMs,
      int monthlyDeletions,
      int unansweredNags,
      List<String> alerts,
      boolean canNag,
      String primaryGoalName,
      Long primaryGoalDaysRemaining,
      GoalUrgency primaryGoalUrgency,
      PresenceService.ActivityView activity) {}

  /** 学员详情。 */
  public record LearnerDetail(
      String userId,
      String username,
      String displayName,
      PresenceView presence,
      DayView day,
      List<TodoRepository.Deletion> deletions,
      StatsView stats) {}

  /** 时间线条目。 */
  public record FeedItem(
      FeedKind kind,
      String learnerUserId,
      Instant occurredAt,
      String title,
      String tag,
      String status,
      String reasonText,
      boolean sentByMe) {}

  /** 学员报告行。 */
  public record LearnerReport(
      String userId,
      String username,
      String displayName,
      StatsView stats,
      int nagCount,
      int nagRespondedCount) {}
}
