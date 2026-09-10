package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserStatus;
import com.shangan.identity.infrastructure.UserRepository;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.supervision.application.SupervisionService;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.application.TodoDeletionService.NotificationRule;
import com.shangan.todo.domain.DeletionReasonTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 删除行为触发的三条督学提醒规则。
 *
 * <p>规则：单日删除数达到 3 条、原因为 GAVE_UP、删除进度过半的课程 Todo。没有主督学人时一条都不产生。
 */
@ExtendWith(MockitoExtension.class)
class SupervisorNotificationRulesTest {

  private static final Instant NOW = Instant.parse("2026-09-07T13:00:00Z");
  private static final String SUPERVISOR = "supervisor-1";

  @Mock private TodoRepository todos;
  @Mock private TodoService todoService;
  @Mock private TodoAttachmentService attachments;
  @Mock private CourseRepository courses;
  @Mock private SupervisionService supervisions;
  @Mock private NagPolicyResolver nagPolicies;
  @Mock private UserRepository users;
  private UserTimeService userTime;
  @Mock private EffectiveActionRecorder effectiveAction;

  private TodoDeletionService service;

  @BeforeEach
  void setUp() {
    // 使用真实时区换算，仓储为替身，不连接数据库。
    lenient()
        .when(users.findById(TodoFixtures.USER_ID))
        .thenReturn(
            Optional.of(
                new User(
                    TodoFixtures.USER_ID,
                    "learner",
                    "unused",
                    "学员",
                    "Asia/Shanghai",
                    UserStatus.ACTIVE,
                    null,
                    null)));
    userTime = new UserTimeService(users, Clock.fixed(NOW, ZoneOffset.UTC));
    service = serviceWith(TodoFixtures.nagPolicyAllEnabled());
  }

  /** 按给定策略重建服务，便于验证开关关闭后不再产生提醒。 */
  private TodoDeletionService serviceWith(EffectiveNagPolicy policy) {
    lenient().when(nagPolicies.resolve(anyString())).thenReturn(policy);
    return new TodoDeletionService(
        todos,
        todoService,
        attachments,
        courses,
        supervisions,
        nagPolicies,
        userTime,
        effectiveAction,
        (IdGenerator) () -> "deletion-1",
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("原因为「放弃了」时立即通知主督学人")
  void 放弃原因触发通知() {
    Todo todo = TodoFixtures.task().build();
    stub(todo, SUPERVISOR, 1);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.GAVE_UP, "这门课先放弃了", 5));

    assertThat(outcome.notifications())
        .extracting(TodoDeletionService.Notification::rule)
        .containsExactly(NotificationRule.GAVE_UP);
    assertThat(outcome.notifications())
        .allSatisfy(
            notification -> assertThat(notification.supervisorUserId()).isEqualTo(SUPERVISOR));
  }

  @Test
  @DisplayName("删除已看过半的课程 Todo 时通知主督学人")
  void 半程删除触发通知() {
    Todo todo =
        TodoFixtures.course()
            .targetProgressPermille(1000)
            .progressPositionMs(900_000L)
            .watchedMs(900_000L)
            .status(TodoStatus.IN_PROGRESS)
            .build();
    stub(todo, SUPERVISOR, 1);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(
                DeletionReasonTag.SWITCHED_TO_OTHER, "改看别的课程", 5));

    assertThat(outcome.notifications())
        .extracting(TodoDeletionService.Notification::rule)
        .containsExactly(NotificationRule.HALF_DONE_DELETE);
  }

  @Test
  @DisplayName("单日删除数达到 3 条时追加一条批量删除提醒，并带上当日删除条数")
  void 单日批量删除触发通知() {
    Todo todo = TodoFixtures.task().build();
    stub(todo, SUPERVISOR, 3);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.TOO_MANY_PLANNED, "今天排太多了", 5));

    assertThat(outcome.notifications())
        .extracting(TodoDeletionService.Notification::rule, TodoDeletionService.Notification::count)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationRule.BULK_DELETE, 3));
  }

  @Test
  @DisplayName("单日删除数为 2 条时不触发批量删除提醒")
  void 单日两条不触发批量提醒() {
    Todo todo = TodoFixtures.task().build();
    stub(todo, SUPERVISOR, 2);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.TEMP_BUSY, "临时有事去不了", 5));

    assertThat(outcome.notifications()).isEmpty();
  }

  @Test
  @DisplayName("没有进度的课程 Todo 被删除时不触发半程删除提醒")
  void 零进度不触发半程提醒() {
    Todo todo = TodoFixtures.course().targetProgressPermille(1000).progressPositionMs(0L).build();
    stub(todo, SUPERVISOR, 1);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.ADDED_BY_MISTAKE, "选错了课时", 5));

    assertThat(outcome.notifications()).isEmpty();
  }

  @Test
  @DisplayName("学员没有主督学人时任何规则都不产生通知")
  void 无督学人不产生通知() {
    Todo todo =
        TodoFixtures.course().targetProgressPermille(1000).progressPositionMs(900_000L).build();
    stub(todo, null, 5);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.GAVE_UP, "这门课先放弃了", 5));

    assertThat(outcome.notifications()).isEmpty();
  }

  @Test
  @DisplayName("同时命中放弃与半程删除时产生两条提醒")
  void 同时命中两条规则() {
    Todo todo =
        TodoFixtures.course().targetProgressPermille(1000).progressPositionMs(900_000L).build();
    stub(todo, SUPERVISOR, 1);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.GAVE_UP, "这门课先放弃了", 5));

    assertThat(outcome.notifications())
        .extracting(TodoDeletionService.Notification::rule)
        .containsExactly(NotificationRule.GAVE_UP, NotificationRule.HALF_DONE_DELETE);
  }

  @Test
  @DisplayName("关闭「删除半程课程」开关后，即使已看过半也不产生提醒")
  void 关闭半程开关不产生提醒() {
    Todo todo =
        TodoFixtures.course()
            .targetProgressPermille(1000)
            .progressPositionMs(900_000L)
            .status(TodoStatus.IN_PROGRESS)
            .build();
    stub(todo, SUPERVISOR, 1);
    TodoDeletionService gated = serviceWith(TodoFixtures.nagPolicy(true, true, false));

    TodoDeletionService.DeletionOutcome outcome =
        gated.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(
                DeletionReasonTag.SWITCHED_TO_OTHER, "改看别的课程", 5));

    assertThat(outcome.notifications()).isEmpty();
  }

  @Test
  @DisplayName("关闭「放弃了」开关后不再通知主督学人")
  void 关闭放弃开关不产生提醒() {
    Todo todo = TodoFixtures.task().build();
    stub(todo, SUPERVISOR, 1);
    TodoDeletionService gated = serviceWith(TodoFixtures.nagPolicy(true, false, true));

    TodoDeletionService.DeletionOutcome outcome =
        gated.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.GAVE_UP, "这门课先放弃了", 5));

    assertThat(outcome.notifications()).isEmpty();
  }

  @Test
  @DisplayName("关闭「单日删除 ≥ 3 条」开关后不产生批量删除提醒")
  void 关闭批量开关不产生提醒() {
    Todo todo = TodoFixtures.task().build();
    stub(todo, SUPERVISOR, 3);
    TodoDeletionService gated = serviceWith(TodoFixtures.nagPolicy(false, true, true));

    TodoDeletionService.DeletionOutcome outcome =
        gated.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.TOO_MANY_PLANNED, "今天排太多了", 5));

    assertThat(outcome.notifications()).isEmpty();
  }

  @Test
  @DisplayName("半程判定按实际进度而非目标值：目标 1000‰ 但只看了 100‰ 时不提醒")
  void 目标高但实际进度低不触发半程提醒() {
    Todo todo =
        TodoFixtures.course()
            .targetProgressPermille(1000)
            .progressPositionMs(180_000L)
            .status(TodoStatus.IN_PROGRESS)
            .build();
    stub(todo, SUPERVISOR, 1);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(
                DeletionReasonTag.SWITCHED_TO_OTHER, "改看别的课程", 5));

    assertThat(outcome.notifications()).isEmpty();
  }

  @Test
  @DisplayName("半程判定按实际进度而非目标值：目标 400‰ 但已看满时仍要提醒")
  void 目标低但实际看满触发半程提醒() {
    Todo todo =
        TodoFixtures.course()
            .targetProgressPermille(400)
            .progressPositionMs(1_800_000L)
            .status(TodoStatus.DONE)
            .build();
    stub(todo, SUPERVISOR, 1);

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(
                DeletionReasonTag.SWITCHED_TO_OTHER, "改看别的课程", 5));

    assertThat(outcome.notifications())
        .extracting(TodoDeletionService.Notification::rule)
        .containsExactly(NotificationRule.HALF_DONE_DELETE);
  }

  private void stub(Todo todo, String supervisorUserId, int deletionsToday) {
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID))
        .thenReturn(Optional.ofNullable(supervisorUserId));
    when(todos.countDeletionsBetween(
            TodoFixtures.USER_ID,
            Instant.parse("2026-09-06T16:00:00Z"),
            Instant.parse("2026-09-07T16:00:00Z")))
        .thenReturn(deletionsToday);
    // 30 分钟课时：position 900 秒正好等于 500‰，用于压住半程判定的边界。
    lenient()
        .when(courses.findResourceById("resource-1"))
        .thenReturn(Optional.of(TodoFixtures.videoResource(1_800_000L)));
  }
}
