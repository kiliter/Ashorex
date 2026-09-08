package com.shangan.supervision.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.goal.application.ExamGoalService;
import com.shangan.goal.domain.GoalUrgency;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.application.PresenceService;
import com.shangan.presence.domain.PresenceState;
import com.shangan.stats.application.StatsService;
import com.shangan.supervision.domain.Supervision;
import com.shangan.supervision.domain.SupervisionKind;
import com.shangan.supervision.domain.SupervisionPermission;
import com.shangan.todo.application.TodoViewService;
import com.shangan.todo.application.TodoViewService.DayView;
import com.shangan.todo.domain.DeletionReasonTag;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 督学端只读视图：越权拒绝、异常徽标口径与不可查看的绑定被跳过。 */
@ExtendWith(MockitoExtension.class)
class SupervisorViewServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
  private static final String SUPERVISOR = "supervisor-1";
  private static final String LEARNER = "learner-1";
  private static final User LEARNER_USER =
      new User(
          LEARNER,
          "lisi",
          "hash",
          "李四",
          "Asia/Shanghai",
          UserStatus.ACTIVE,
          null,
          Set.of(UserRole.LEARNER));

  @Mock private SupervisionService supervisions;
  @Mock private SupervisionGuard guard;
  @Mock private UserTimeService userTime;
  @Mock private TodoViewService todoViews;
  @Mock private TodoRepository todos;
  @Mock private PresenceService presence;
  @Mock private StatsService stats;
  @Mock private NagRepository nags;
  @Mock private ExamGoalService goals;

  private SupervisorViewService service;

  @BeforeEach
  void setUp() {
    service =
        new SupervisorViewService(
            supervisions, guard, userTime, todoViews, todos, presence, stats, nags, goals);
  }

  @Test
  void 报告催办次数使用统计区间而不是最近一百条() {
    when(supervisions.learnersOf(SUPERVISOR)).thenReturn(List.of(binding(true, true)));
    when(userTime.requireUser(LEARNER)).thenReturn(LEARNER_USER);
    var view = org.mockito.Mockito.mock(StatsService.StatsView.class);
    when(view.start()).thenReturn(TODAY);
    when(view.end()).thenReturn(TODAY.plusDays(6));
    when(stats.stats(LEARNER, "WEEK", null)).thenReturn(view);
    when(nags.countResponsesBetween(LEARNER, TODAY, TODAY.plusDays(6)))
        .thenReturn(new NagRepository.ResponseCounts(120, 80));
    var report = service.report(SUPERVISOR, "WEEK", null).getFirst();
    assertThat(report.nagCount()).isEqualTo(120);
    assertThat(report.nagRespondedCount()).isEqualTo(80);
    org.mockito.Mockito.verify(nags, org.mockito.Mockito.never())
        .findByUser(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void 时间线我发的由当前督学实际发起人判定() {
    when(supervisions.learnersOf(SUPERVISOR)).thenReturn(List.of(binding(true, true)));
    when(userTime.requireUser(LEARNER)).thenReturn(LEARNER_USER);
    when(userTime.today(LEARNER_USER)).thenReturn(TODAY);
    when(nags.findByUsers(List.of(LEARNER), 50))
        .thenReturn(
            List.of(
                nag(NagStatus.PENDING, NagTrigger.SUPERVISOR, SUPERVISOR),
                nag(NagStatus.PENDING, NagTrigger.SUPERVISOR, "other-supervisor"),
                nag(NagStatus.PENDING, NagTrigger.MANUAL, null)));
    assertThat(service.feed(SUPERVISOR, 50))
        .extracting(SupervisorViewService.FeedItem::sentByMe)
        .containsExactly(true, false, false);
  }

  @Test
  @DisplayName("学员详情必须先过督学鉴权，越权时向上抛 SUPERVISION_FORBIDDEN")
  void 学员详情先过鉴权() {
    when(guard.requirePermission(SUPERVISOR, LEARNER, SupervisionPermission.VIEW))
        .thenThrow(
            new BusinessException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "SUPERVISION_FORBIDDEN",
                "没有该学员的督学权限"));

    assertThatThrownBy(() -> service.learnerDetail(SUPERVISOR, LEARNER, "DAY", null))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("SUPERVISION_FORBIDDEN");
  }

  @Test
  @DisplayName("没有查看权限的绑定不出现在学员总览中")
  void 无查看权限绑定被跳过() {
    when(supervisions.learnersOf(SUPERVISOR)).thenReturn(List.of(binding(false, true)));

    assertThat(service.learners(SUPERVISOR)).isEmpty();
  }

  @Test
  @DisplayName("有 Todo 但零完成、离线、月删除达 5 次且有未回应催办时四个徽标同时亮起")
  void 四类异常徽标() {
    when(supervisions.learnersOf(SUPERVISOR)).thenReturn(List.of(binding(true, true)));
    when(userTime.requireUser(LEARNER)).thenReturn(LEARNER_USER);
    when(userTime.today(LEARNER_USER)).thenReturn(TODAY);
    when(todoViews.day(LEARNER, TODAY.toString())).thenReturn(dayView(4, 0));
    when(presence.view(LEARNER_USER)).thenReturn(presenceView(PresenceState.OFFLINE));
    when(userTime.startOfDay(LEARNER_USER, TODAY.withDayOfMonth(1)))
        .thenReturn(instant(TODAY.withDayOfMonth(1)));
    when(userTime.endOfDayExclusive(LEARNER_USER, TODAY)).thenReturn(instant(TODAY.plusDays(1)));
    when(todos.findDeletions(LEARNER, instant(TODAY.withDayOfMonth(1)), instant(TODAY.plusDays(1))))
        .thenReturn(deletions(5));
    when(nags.findByUser(LEARNER, 50)).thenReturn(List.of(nag(NagStatus.DELIVERED)));

    List<SupervisorViewService.LearnerOverview> overviews = service.learners(SUPERVISOR);

    assertThat(overviews)
        .singleElement()
        .satisfies(
            overview -> {
              assertThat(overview.userId()).isEqualTo(LEARNER);
              assertThat(overview.displayName()).isEqualTo("李四");
              assertThat(overview.todayTotal()).isEqualTo(4);
              assertThat(overview.todayDone()).isZero();
              assertThat(overview.monthlyDeletions()).isEqualTo(5);
              assertThat(overview.unansweredNags()).isEqualTo(1);
              assertThat(overview.canNag()).isTrue();
              assertThat(overview.alerts())
                  .containsExactly("ZERO_DONE", "OFFLINE", "FREQUENT_DELETION", "NAG_UNANSWERED");
            });
  }

  @Test
  @DisplayName("在线、有完成、删除不频繁且催办已回应时不产生任何徽标")
  void 正常学员无徽标() {
    when(supervisions.learnersOf(SUPERVISOR)).thenReturn(List.of(binding(true, false)));
    when(userTime.requireUser(LEARNER)).thenReturn(LEARNER_USER);
    when(userTime.today(LEARNER_USER)).thenReturn(TODAY);
    when(todoViews.day(LEARNER, TODAY.toString())).thenReturn(dayView(4, 3));
    when(presence.view(LEARNER_USER)).thenReturn(presenceView(PresenceState.ONLINE));
    when(userTime.startOfDay(LEARNER_USER, TODAY.withDayOfMonth(1)))
        .thenReturn(instant(TODAY.withDayOfMonth(1)));
    when(userTime.endOfDayExclusive(LEARNER_USER, TODAY)).thenReturn(instant(TODAY.plusDays(1)));
    when(todos.findDeletions(LEARNER, instant(TODAY.withDayOfMonth(1)), instant(TODAY.plusDays(1))))
        .thenReturn(deletions(1));
    when(nags.findByUser(LEARNER, 50)).thenReturn(List.of(nag(NagStatus.RESPONDED)));

    assertThat(service.learners(SUPERVISOR))
        .singleElement()
        .satisfies(
            overview -> {
              assertThat(overview.alerts()).isEmpty();
              assertThat(overview.canNag()).isFalse();
            });
  }

  @Test
  @DisplayName("学员总览投影主目标名与剩余天数；非主目标不参与")
  void 投影主目标与剩余天数() {
    stubBaseLearner();
    when(goals.list(LEARNER))
        .thenReturn(
            List.of(
                goalView("goal-2", "中级会计", 200, false, GoalUrgency.NORMAL),
                goalView("goal-1", "注会", 82, true, GoalUrgency.NORMAL)));

    assertThat(service.learners(SUPERVISOR))
        .singleElement()
        .satisfies(
            overview -> {
              assertThat(overview.primaryGoalName()).isEqualTo("注会");
              assertThat(overview.primaryGoalDaysRemaining()).isEqualTo(82L);
              assertThat(overview.primaryGoalUrgency()).isEqualTo(GoalUrgency.NORMAL);
            });
  }

  @Test
  @DisplayName("学员没有任何考试目标时三个主目标字段全为 null，客户端据此隐藏该行")
  void 无目标时主目标字段为空() {
    stubBaseLearner();
    when(goals.list(LEARNER)).thenReturn(List.of());

    assertThat(service.learners(SUPERVISOR))
        .singleElement()
        .satisfies(
            overview -> {
              assertThat(overview.primaryGoalName()).isNull();
              assertThat(overview.primaryGoalDaysRemaining()).isNull();
              assertThat(overview.primaryGoalUrgency()).isNull();
            });
  }

  /** 主目标用例只关心目标字段，其余依赖用一份最小可用的正常学员打桩。 */
  private void stubBaseLearner() {
    when(supervisions.learnersOf(SUPERVISOR)).thenReturn(List.of(binding(true, true)));
    when(userTime.requireUser(LEARNER)).thenReturn(LEARNER_USER);
    when(userTime.today(LEARNER_USER)).thenReturn(TODAY);
    when(todoViews.day(LEARNER, TODAY.toString())).thenReturn(dayView(4, 3));
    when(presence.view(LEARNER_USER)).thenReturn(presenceView(PresenceState.ONLINE));
    when(userTime.startOfDay(LEARNER_USER, TODAY.withDayOfMonth(1)))
        .thenReturn(instant(TODAY.withDayOfMonth(1)));
    when(userTime.endOfDayExclusive(LEARNER_USER, TODAY)).thenReturn(instant(TODAY.plusDays(1)));
    when(todos.findDeletions(LEARNER, instant(TODAY.withDayOfMonth(1)), instant(TODAY.plusDays(1))))
        .thenReturn(deletions(0));
    when(nags.findByUser(LEARNER, 50)).thenReturn(List.of());
  }

  private static ExamGoalService.GoalView goalView(
      String id, String name, long daysRemaining, boolean primary, GoalUrgency urgency) {
    return new ExamGoalService.GoalView(
        id, name, TODAY.plusDays(daysRemaining), "", primary, daysRemaining, urgency);
  }

  private static Supervision binding(boolean canView, boolean canNag) {
    return new Supervision(
        "supervision-1",
        LEARNER,
        SUPERVISOR,
        SupervisionKind.PRIMARY,
        canView,
        canNag,
        false,
        false,
        null);
  }

  private static DayView dayView(int total, int done) {
    return new DayView(
        TODAY,
        true,
        false,
        List.of(),
        new TodoViewService.DaySummaryTotals(total, done, 600_000L, 300_000L, 0),
        0);
  }

  private static PresenceService.PresenceView presenceView(PresenceState state) {
    return new PresenceService.PresenceView(
        LEARNER,
        state,
        instant(TODAY),
        instant(TODAY),
        12,
        "FOREGROUND",
        "2.0.0",
        TODAY.toString(),
        null);
  }

  private static List<TodoRepository.Deletion> deletions(int count) {
    List<TodoRepository.Deletion> rows = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      rows.add(
          new TodoRepository.Deletion(
              "deletion-" + index,
              LEARNER,
              "todo-" + index,
              TodoType.TASK,
              TODAY,
              "标题",
              null,
              "{}",
              DeletionReasonTag.TEMP_BUSY,
              "临时有事情",
              SUPERVISOR,
              instant(TODAY)));
    }
    return rows;
  }

  private static Nag nag(NagStatus status) {
    return nag(status, NagTrigger.AUTO, null);
  }

  /** 构造不同发起人的催办，验证展示归属而非只按触发类型猜测。 */
  private static Nag nag(NagStatus status, NagTrigger trigger, String actor) {
    return new Nag(
        "nag-1",
        LEARNER,
        TODAY,
        1,
        trigger,
        actor,
        95,
        3,
        "还有 3 项没做",
        true,
        status,
        instant(TODAY),
        null,
        null,
        null,
        null,
        instant(TODAY));
  }

  private static Instant instant(LocalDate date) {
    return date.atStartOfDay(ZONE).toInstant();
  }
}
