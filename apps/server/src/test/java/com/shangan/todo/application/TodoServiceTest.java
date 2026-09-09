package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.supervision.application.SupervisionService;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Todo 创建、修改与顺延：三种类型的必填校验与服务端日期归属。 */
@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T18:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);
  private static final User USER =
      new User(
          TodoFixtures.USER_ID,
          "demo",
          "hash",
          "小明",
          "Asia/Shanghai",
          UserStatus.ACTIVE,
          null,
          Set.of(UserRole.LEARNER));

  @Mock private TodoRepository todos;
  @Mock private CatalogQueryService catalog;
  @Mock private UserTimeService userTime;
  @Mock private SupervisionService supervisions;
  @Mock private EffectiveActionRecorder effectiveAction;

  private TodoService service;

  @BeforeEach
  void setUp() {
    service =
        new TodoService(
            todos,
            catalog,
            userTime,
            supervisions,
            effectiveAction,
            () -> "todo-new",
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("创建后改绑时，完成快照使用完成时的主督学人")
  void 完成采用当前督学人() {
    Todo todo = TodoFixtures.course().build();
    when(supervisions.primarySupervisorOf(todo.userId())).thenReturn(Optional.of("new-supervisor"));
    service.snapshotCompletion(todo);
    verify(todos).updateCompletionSupervisor(todo.id(), "new-supervisor");
  }

  @Test
  @DisplayName("已完成待办继续播放不再改写督学人历史快照")
  void 完成快照不随重放改变() {
    service.snapshotCompletion(TodoFixtures.course().status(TodoStatus.DONE).build());
    org.mockito.Mockito.verifyNoInteractions(supervisions, todos);
  }

  @Test
  @DisplayName("未传日期时按用户时区落到「今天」：UTC 18:00 在东八区已是次日")
  void 日期按用户时区生成() {
    stubCreate();
    when(catalog.requireVisibleResource("resource-1")).thenReturn(video(600_000L));

    List<Todo> created =
        service.create(
            TodoFixtures.USER_ID,
            List.of(
                new TodoService.CreateTodoCommand(
                    TodoType.COURSE, null, null, null, "resource-1", 500, null, false, null)));

    assertThat(created)
        .singleElement()
        .satisfies(
            todo -> {
              assertThat(todo.localDate()).isEqualTo(TODAY);
              assertThat(todo.id()).isEqualTo("todo-new");
              assertThat(todo.title()).isEqualTo("第 1 讲");
              assertThat(todo.targetProgressPermille()).isEqualTo(500);
              assertThat(todo.status()).isEqualTo(TodoStatus.TODO);
              assertThat(todo.focusState()).isEqualTo(FocusState.IDLE);
              assertThat(todo.supervisorUserIdSnapshot()).isEqualTo("supervisor-1");
            });
    verify(todos).insert(created.getFirst(), NOW);
  }

  @Test
  @DisplayName("课程待办未选课时返回 TODO_RESOURCE_REQUIRED")
  void 课程必须选课时() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    TodoFixtures.USER_ID,
                    List.of(
                        new TodoService.CreateTodoCommand(
                            TodoType.COURSE, null, null, null, null, null, null, false, null))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_RESOURCE_REQUIRED");
    verify(todos, never()).insert(any(), any());
  }

  @Test
  @DisplayName("目标进度只允许 1 到 1000 千分比")
  void 目标进度范围校验() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    TodoFixtures.USER_ID,
                    List.of(
                        new TodoService.CreateTodoCommand(
                            TodoType.COURSE,
                            null,
                            null,
                            null,
                            "resource-1",
                            1001,
                            null,
                            false,
                            null))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_TARGET_INVALID");
  }

  @Test
  @DisplayName("缺少时长的课时不能加入待办")
  void 无时长课时被拒绝() {
    stubCreateWithoutSupervisor();
    when(catalog.requireVisibleResource("resource-1")).thenReturn(video(null));

    assertThatThrownBy(
            () ->
                service.create(
                    TodoFixtures.USER_ID,
                    List.of(
                        new TodoService.CreateTodoCommand(
                            TodoType.COURSE,
                            null,
                            null,
                            null,
                            "resource-1",
                            1000,
                            null,
                            false,
                            null))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("RESOURCE_NOT_MEASURABLE");
  }

  @Test
  @DisplayName("专注待办的倒计时必须在 60 到 43200 秒之间")
  void 倒计时范围校验() {
    stubCreateWithoutSupervisor();

    assertThatThrownBy(
            () ->
                service.create(
                    TodoFixtures.USER_ID,
                    List.of(
                        new TodoService.CreateTodoCommand(
                            TodoType.FOCUS, null, "专注刷题", null, null, null, 59, false, null))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_PLANNED_INVALID");
  }

  @Test
  @DisplayName("待办事项必须有标题")
  void 待办事项必须有标题() {
    stubCreateWithoutSupervisor();

    assertThatThrownBy(
            () ->
                service.create(
                    TodoFixtures.USER_ID,
                    List.of(
                        new TodoService.CreateTodoCommand(
                            TodoType.TASK, null, "   ", null, null, null, null, false, null))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_TITLE_INVALID");
  }

  @Test
  @DisplayName("非法日期格式返回 TODO_DATE_INVALID")
  void 非法日期被拒绝() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    TodoFixtures.USER_ID,
                    List.of(
                        new TodoService.CreateTodoCommand(
                            TodoType.TASK,
                            "2026/09/08",
                            "背单词",
                            null,
                            null,
                            null,
                            null,
                            false,
                            null))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_DATE_INVALID");
  }

  @Test
  @DisplayName("空创建请求被拒绝")
  void 空请求被拒绝() {
    assertThatThrownBy(() -> service.create(TodoFixtures.USER_ID, List.of()))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_EMPTY_REQUEST");
  }

  @Test
  @DisplayName("单日待办数量达到 40 条时拒绝继续添加")
  void 单日数量上限() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID)).thenReturn(Optional.empty());
    List<Todo> existing = new java.util.ArrayList<>();
    for (int index = 0; index < 40; index++) {
      existing.add(TodoFixtures.task().id("todo-" + index).build());
    }
    when(todos.findByUserAndDate(TodoFixtures.USER_ID, TODAY)).thenReturn(existing);

    assertThatThrownBy(
            () ->
                service.create(
                    TodoFixtures.USER_ID,
                    List.of(
                        new TodoService.CreateTodoCommand(
                            TodoType.TASK, null, "背单词", null, null, null, null, false, null))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_DAILY_LIMIT");
  }

  @Test
  @DisplayName("顺延只改日期与排序，保留进度、时长与附件")
  void 顺延保留进度() {
    Todo todo =
        TodoFixtures.course()
            .localDate(TODAY)
            .progressPositionMs(120_000L)
            .watchedMs(120_000L)
            .status(TodoStatus.IN_PROGRESS)
            .build();
    when(todos.findById(todo.id())).thenReturn(Optional.of(todo));
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(todos.nextSortOrder(TodoFixtures.USER_ID, LocalDate.of(2026, 9, 9))).thenReturn(3);

    service.defer(TodoFixtures.USER_ID, todo.id(), "2026-09-09");

    verify(todos).updateLocalDate(todo.id(), LocalDate.of(2026, 9, 9), 3, NOW);
    verify(todos, never())
        .updateProgress(
            anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyInt(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyString(),
            any());
    verify(effectiveAction).record(TodoFixtures.USER_ID);
  }

  @Test
  @DisplayName("只有课程待办可以改目标进度")
  void 目标进度仅限课程待办() {
    Todo todo = TodoFixtures.task().build();
    when(todos.findById(todo.id())).thenReturn(Optional.of(todo));

    assertThatThrownBy(
            () ->
                service.patch(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoService.PatchTodoCommand(null, null, 800, null, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_TARGET_NOT_APPLICABLE");
  }

  @Test
  @DisplayName("只有专注待办可以改倒计时")
  void 倒计时仅限专注待办() {
    Todo todo = TodoFixtures.task().build();
    when(todos.findById(todo.id())).thenReturn(Optional.of(todo));

    assertThatThrownBy(
            () ->
                service.patch(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoService.PatchTodoCommand(null, null, null, 600, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_PLANNED_NOT_APPLICABLE");
  }

  @Test
  @DisplayName("重排列表包含不属于该日期的待办时整体拒绝")
  void 重排校验归属() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(todos.findByUserAndDate(TodoFixtures.USER_ID, TODAY))
        .thenReturn(List.of(TodoFixtures.task().id("todo-1").build()));

    assertThatThrownBy(
            () -> service.reorder(TodoFixtures.USER_ID, null, List.of("todo-1", "todo-x")))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_REORDER_MISMATCH");
    verify(todos).updateSortOrder("todo-1", 0, NOW);
  }

  @Test
  @DisplayName("访问他人待办按不存在处理")
  void 越权访问视为不存在() {
    Todo todo = TodoFixtures.task().userId("other-user").build();
    when(todos.findById(todo.id())).thenReturn(Optional.of(todo));

    assertThatThrownBy(() -> service.requireOwned(TodoFixtures.USER_ID, todo.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_NOT_FOUND");
  }

  @Test
  @DisplayName("批量创建时逐条写库并只记一次有效操作")
  void 批量创建() {
    stubCreateWithoutSupervisor();

    List<Todo> created =
        service.create(
            TodoFixtures.USER_ID,
            List.of(
                new TodoService.CreateTodoCommand(
                    TodoType.TASK, null, "背单词", null, null, null, null, false, null),
                new TodoService.CreateTodoCommand(
                    TodoType.FOCUS, null, "专注刷题", null, null, null, 1_500, true, null)));

    assertThat(created).hasSize(2);
    assertThat(created).extracting(Todo::todoType).containsExactly(TodoType.TASK, TodoType.FOCUS);
    assertThat(created.get(1).plannedSeconds()).isEqualTo(1_500);
    assertThat(created.get(1).requireEvidence()).isTrue();
    ArgumentCaptor<Todo> inserted = ArgumentCaptor.forClass(Todo.class);
    verify(todos, org.mockito.Mockito.times(2)).insert(inserted.capture(), any());
    assertThat(inserted.getAllValues()).hasSize(2);
    verify(effectiveAction, org.mockito.Mockito.times(1)).record(TodoFixtures.USER_ID);
  }

  /** 同日同课时请求重放应跳过，不额外创建 Todo。 */
  @Test
  void duplicateCourseIsSkipped() {
    stubCreateWithoutSupervisor();
    var existing = TodoFixtures.course().localDate(TODAY).targetProgressPermille(500).build();
    when(todos.findByUserAndDate(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of(existing));
    org.mockito.Mockito.lenient()
        .when(catalog.requireVisibleResource("resource-1"))
        .thenReturn(video(600_000L));
    var result =
        service.create(
            TodoFixtures.USER_ID,
            List.of(
                new TodoService.CreateTodoCommand(
                    TodoType.COURSE, null, null, null, "resource-1", 500, null, false, null)));
    assertThat(result).isEmpty();
    verify(todos, never()).insert(any(), any());
  }

  @Test
  void historyIsSkippedWithoutExplicitConfirmation() {
    stubCreateWithoutSupervisor();
    var old = TodoFixtures.course().localDate(TODAY.minusDays(1)).build();
    when(todos.findPendingBefore(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of(old));
    var result = service.addCourses(TodoFixtures.USER_ID, List.of(courseCommand()), List.of());
    assertThat(result.created()).isZero();
    assertThat(result.deferred()).isZero();
    assertThat(result.skipped()).isEqualTo(1);
    verify(todos, never()).insert(any(), any());
    verify(todos, never()).updateLocalDate(anyString(), any(), anyInt(), any());
  }

  @Test
  void confirmedHistoryKeepsOriginalDateAndRecords() {
    stubCreateWithoutSupervisor();
    var old =
        TodoFixtures.course()
            .id("old")
            .localDate(TODAY.minusDays(1))
            .progressPositionMs(30000)
            .watchedMs(15000)
            .build();
    when(todos.findById("old")).thenReturn(Optional.of(old));
    when(todos.findPendingBefore(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of(old));
    var result = service.addCourses(TodoFixtures.USER_ID, List.of(courseCommand()), List.of("old"));
    assertThat(result.deferred()).isZero();
    assertThat(result.reused()).isEqualTo(1);
    assertThat(result.created()).isZero();
    assertThat(result.accepted()).containsExactly(old);
    verify(todos, never()).updateLocalDate(anyString(), any(), anyInt(), any());
    verify(todos, never()).insert(any(), any());
    verify(todos, never())
        .updateProgress(
            anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyInt(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyString(),
            any());
  }

  @Test
  void duplicateWithinBatchCreatesOnlyOne() {
    stubCreateWithoutSupervisor();
    when(catalog.requireVisibleResource("resource-1")).thenReturn(video(600000L));
    var result =
        service.addCourses(
            TodoFixtures.USER_ID, List.of(courseCommand(), courseCommand()), List.of());
    assertThat(result.created()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    verify(todos).insert(any(), any());
  }

  @Test
  void completedHistoricalConfirmationIsRejected() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID)).thenReturn(Optional.empty());
    when(todos.findById("old"))
        .thenReturn(
            Optional.of(
                TodoFixtures.course()
                    .id("old")
                    .localDate(TODAY.minusDays(1))
                    .status(TodoStatus.DONE)
                    .build()));
    assertThatThrownBy(
            () ->
                service.addCourses(TodoFixtures.USER_ID, List.of(courseCommand()), List.of("old")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("重新预览");
    verify(todos, never()).updateLocalDate(anyString(), any(), anyInt(), any());
  }

  /** 复用今日未完成项时提高目标，但不修改已看位置、时长或完成状态。 */
  @Test
  void sameDayReuseRaisesTargetWithoutResettingProgress() {
    stubCreateWithoutSupervisor();
    var old =
        TodoFixtures.course()
            .id("old")
            .localDate(TODAY)
            .targetProgressPermille(500)
            .progressPositionMs(30000)
            .watchedMs(15000)
            .build();
    when(todos.findById("old")).thenReturn(Optional.of(old));
    when(todos.findByUserAndDate(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of(old));
    var result = service.addCourses(TodoFixtures.USER_ID, List.of(courseCommand()), List.of("old"));
    verify(todos)
        .updateEditableFields(
            "old", old.title(), old.note(), 1000, old.plannedSeconds(), old.requireEvidence(), NOW);
    verify(todos, never()).insert(any(), any());
    verify(todos, never())
        .updateProgress(
            anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyInt(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyString(),
            any());
    assertThat(result.created()).isZero();
    assertThat(result.skipped()).isZero();
  }

  @Test
  void deferringNeverLowersExistingTarget() {
    stubCreateWithoutSupervisor();
    var old =
        TodoFixtures.course()
            .id("old")
            .localDate(TODAY.minusDays(1))
            .targetProgressPermille(1000)
            .build();
    when(todos.findById("old")).thenReturn(Optional.of(old));
    when(todos.findPendingBefore(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of(old));
    var request =
        new TodoService.CreateTodoCommand(
            TodoType.COURSE, null, null, null, "resource-1", 500, null, false, null);
    assertThat(service.addCourses(TodoFixtures.USER_ID, List.of(request), List.of("old")).reused())
        .isEqualTo(1);
    verify(todos, never())
        .updateEditableFields(
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            any());
  }

  @Test
  void conflictingBatchTargetsCreateOnceWithHighestTarget() {
    stubCreateWithoutSupervisor();
    when(catalog.requireVisibleResource("resource-1")).thenReturn(video(600000L));
    var lower =
        new TodoService.CreateTodoCommand(
            TodoType.COURSE, null, null, null, "resource-1", 500, null, false, null);
    var result =
        service.addCourses(TodoFixtures.USER_ID, List.of(lower, courseCommand()), List.of());
    assertThat(result.created()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.accepted().getFirst().targetProgressPermille()).isEqualTo(1000);
  }

  @Test
  void resultDoesNotExposeInternalAcceptedTodos() throws Exception {
    var result =
        new TodoService.CourseAdditionResult(
            1, 0, 0, 0, List.of(), List.of(TodoFixtures.course().build()));
    String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(result);
    assertThat(json).contains("\"created\":1").doesNotContain("accepted", "progressPositionMs");
  }

  @Test
  void previewDeduplicatesBatchWithoutWriting() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(catalog.requireVisibleResource("resource-1")).thenReturn(video(600000L));
    var result =
        service.previewCourseAdditions(
            TodoFixtures.USER_ID, List.of(courseCommand(), courseCommand()));
    assertThat(result.items())
        .extracting(TodoService.CourseAdditionItem::status)
        .containsExactly("NEW", "DUPLICATE");
    verify(todos, never()).insert(any(), any());
    verify(todos, never()).updateLocalDate(anyString(), any(), anyInt(), any());
  }

  /** 下架后历史复用和普通顺延都不得再写入学习安排。 */
  @Test
  void unavailableHistoryCannotBeReusedOrDeferred() {
    stubCreateWithoutSupervisor();
    var old = TodoFixtures.course().id("old").localDate(TODAY.minusDays(1)).build();
    when(todos.findById("old")).thenReturn(Optional.of(old));
    when(todos.findPendingBefore(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of(old));
    when(catalog.requireVisibleResource("resource-1"))
        .thenThrow(
            new BusinessException(
                org.springframework.http.HttpStatus.CONFLICT, "RESOURCE_UNAVAILABLE", "课时已下架"));
    assertThatThrownBy(
            () ->
                service.addCourses(TodoFixtures.USER_ID, List.of(courseCommand()), List.of("old")))
        .isInstanceOf(BusinessException.class)
        .hasMessage("课时已下架");
    assertThatThrownBy(() -> service.defer(TodoFixtures.USER_ID, "old", "2026-09-08"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("历史待办请在今日还债中继续执行");
    verify(todos, never()).updateLocalDate(anyString(), any(), anyInt(), any());
    verify(effectiveAction, never()).record(anyString());
  }

  /** 纯跳过没有状态变化，即使原课时下架也保持幂等返回。 */
  @Test
  void sameTargetUnavailableCourseStillSkipsWithoutCatalogRead() {
    stubCreateWithoutSupervisor();
    var old = TodoFixtures.course().localDate(TODAY).build();
    when(todos.findByUserAndDate(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of(old));
    assertThat(
            service.addCourses(TodoFixtures.USER_ID, List.of(courseCommand()), List.of()).skipped())
        .isEqualTo(1);
    org.mockito.Mockito.verifyNoInteractions(catalog);
    verify(todos, never()).updateLocalDate(anyString(), any(), anyInt(), any());
  }

  /** 课程添加请求固定默认目标，便于只关注去重与顺延边界。 */
  private TodoService.CreateTodoCommand courseCommand() {
    return new TodoService.CreateTodoCommand(
        TodoType.COURSE, null, null, null, "resource-1", 1000, null, false, null);
  }

  private void stubCreate() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID))
        .thenReturn(Optional.of("supervisor-1"));
    when(todos.findByUserAndDate(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of());
  }

  private void stubCreateWithoutSupervisor() {
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID)).thenReturn(Optional.empty());
    when(todos.findByUserAndDate(TodoFixtures.USER_ID, TODAY)).thenReturn(List.of());
  }

  private static LearningResource video(Long durationMs) {
    return new LearningResource(
        "resource-1",
        "course-1",
        ResourceType.VIDEO,
        "第 1 讲",
        1,
        durationMs,
        null,
        "emby:1",
        null,
        true,
        CatalogStatus.ACTIVE,
        null);
  }
}
