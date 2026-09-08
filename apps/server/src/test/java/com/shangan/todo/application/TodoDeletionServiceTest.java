package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.supervision.application.SupervisionService;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.domain.DeletionReasonTag;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 删除台账：原因校验、台账快照、删除顺序与批量共用原因。 */
@ExtendWith(MockitoExtension.class)
class TodoDeletionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T13:00:00Z");

  @Mock private TodoRepository todos;
  @Mock private TodoService todoService;
  @Mock private TodoAttachmentService attachments;
  @Mock private CourseRepository courses;
  @Mock private SupervisionService supervisions;
  @Mock private NagPolicyResolver nagPolicies;
  @Mock private UserTimeService userTime;
  @Mock private EffectiveActionRecorder effectiveAction;

  private TodoDeletionService service;

  @BeforeEach
  void setUp() {
    // 删除流程每次都会解析催办策略；校验失败的用例走不到这一步，因此用 lenient 避免多余打桩告警。
    lenient().when(nagPolicies.resolve(anyString())).thenReturn(TodoFixtures.nagPolicyAllEnabled());
    service =
        new TodoDeletionService(
            todos,
            todoService,
            attachments,
            courses,
            supervisions,
            nagPolicies,
            userTime,
            effectiveAction,
            fixedIdGenerator(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("缺少原因标签时拒绝删除，错误码 TODO_DELETE_REASON_REQUIRED")
  void 缺少原因标签拒绝() {
    assertThatThrownBy(
            () ->
                service.delete(
                    TodoFixtures.USER_ID,
                    "todo-1",
                    new TodoDeletionService.DeleteCommand(null, "临时有事来不及做", 5)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_DELETE_REASON_REQUIRED");
    verify(todos, never()).insertDeletion(any());
    verify(todos, never()).delete(anyString());
  }

  @Test
  @DisplayName("原因说明短于最小字数时拒绝删除")
  void 原因过短拒绝() {
    assertThatThrownBy(
            () ->
                service.delete(
                    TodoFixtures.USER_ID,
                    "todo-1",
                    new TodoDeletionService.DeleteCommand(DeletionReasonTag.TEMP_BUSY, "忙", 5)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_DELETE_REASON_REQUIRED");
    verify(todos, never()).insertDeletion(any());
  }

  @Test
  @DisplayName("只有空白字符的原因说明按空处理并拒绝")
  void 空白原因拒绝() {
    assertThatThrownBy(
            () ->
                service.delete(
                    TodoFixtures.USER_ID,
                    "todo-1",
                    new TodoDeletionService.DeleteCommand(
                        DeletionReasonTag.TEMP_BUSY, "      ", 5)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_DELETE_REASON_REQUIRED");
  }

  @Test
  @DisplayName("删除成功时写入台账：含进度快照 JSON 与督学人快照")
  void 台账包含进度与督学人快照() {
    Todo todo =
        TodoFixtures.course()
            .status(TodoStatus.IN_PROGRESS)
            .targetProgressPermille(300)
            .progressPositionMs(45_000L)
            .watchedMs(45_000L)
            .build();
    stubDelete(todo, "supervisor-1");

    TodoDeletionService.DeletionOutcome outcome =
        service.delete(
            TodoFixtures.USER_ID,
            todo.id(),
            new TodoDeletionService.DeleteCommand(
                DeletionReasonTag.SWITCHED_TO_OTHER, "  改成先刷真题  ", 5));

    assertThat(outcome.deletedCount()).isEqualTo(1);
    assertThat(outcome.reasonTag()).isEqualTo(DeletionReasonTag.SWITCHED_TO_OTHER);

    ArgumentCaptor<TodoRepository.Deletion> deletion =
        ArgumentCaptor.forClass(TodoRepository.Deletion.class);
    verify(todos).insertDeletion(deletion.capture());
    TodoRepository.Deletion row = deletion.getValue();
    assertThat(row.userId()).isEqualTo(TodoFixtures.USER_ID);
    assertThat(row.todoId()).isEqualTo(todo.id());
    assertThat(row.todoType()).isEqualTo(TodoType.COURSE);
    assertThat(row.titleSnapshot()).isEqualTo("第 1 讲");
    assertThat(row.resourceId()).isEqualTo("resource-1");
    assertThat(row.reasonText()).isEqualTo("改成先刷真题");
    assertThat(row.supervisorUserIdSnapshot()).isEqualTo("supervisor-1");
    assertThat(row.deletedAt()).isEqualTo(NOW);
    assertThat(row.progressSnapshotJson())
        .isEqualTo(
            "{\"status\":\"IN_PROGRESS\",\"positionMs\":45000,\"page\":0,\"watchedMs\":45000,"
                + "\"focusedMs\":0,\"focusState\":\"IDLE\",\"targetPermille\":300}");
  }

  @Test
  @DisplayName("删除顺序固定：先写台账，再删附件与进度流水，最后删 Todo 行")
  void 删除顺序固定() {
    Todo todo = TodoFixtures.task().build();
    stubDelete(todo, null);

    service.delete(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoDeletionService.DeleteCommand(DeletionReasonTag.TEMP_BUSY, "临时加班没时间", 5));

    InOrder order = inOrder(todos, attachments);
    order.verify(todos).insertDeletion(any());
    order.verify(attachments).deleteAllOf(todo.id());
    order.verify(todos).deleteProgressEventsOf(todo.id());
    order.verify(todos).delete(todo.id());
  }

  @Test
  @DisplayName("批量删除共用同一份原因，逐条写台账")
  void 批量删除共用原因() {
    Todo first = TodoFixtures.task().id("todo-1").build();
    Todo second = TodoFixtures.task().id("todo-2").build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, "todo-1")).thenReturn(first);
    when(todoService.requireOwned(TodoFixtures.USER_ID, "todo-2")).thenReturn(second);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID)).thenReturn(Optional.empty());
    when(userTime.today(TodoFixtures.USER_ID)).thenReturn(LocalDate.of(2026, 9, 7));
    when(todos.countDeletionsOn(TodoFixtures.USER_ID, LocalDate.of(2026, 9, 7))).thenReturn(2);

    TodoDeletionService.DeletionOutcome outcome =
        service.deleteAll(
            TodoFixtures.USER_ID,
            List.of("todo-1", "todo-2"),
            new TodoDeletionService.DeleteCommand(DeletionReasonTag.TOO_MANY_PLANNED, "今天排太多了", 5));

    assertThat(outcome.deletedCount()).isEqualTo(2);
    ArgumentCaptor<TodoRepository.Deletion> rows =
        ArgumentCaptor.forClass(TodoRepository.Deletion.class);
    verify(todos, org.mockito.Mockito.times(2)).insertDeletion(rows.capture());
    assertThat(rows.getAllValues())
        .extracting(TodoRepository.Deletion::todoId)
        .containsExactly("todo-1", "todo-2");
    assertThat(rows.getAllValues())
        .allSatisfy(row -> assertThat(row.reasonText()).isEqualTo("今天排太多了"));
    verify(todos).delete("todo-1");
    verify(todos).delete("todo-2");
  }

  @Test
  @DisplayName("空列表删除请求被拒绝")
  void 空列表拒绝() {
    assertThatThrownBy(
            () ->
                service.deleteAll(
                    TodoFixtures.USER_ID,
                    List.of(),
                    new TodoDeletionService.DeleteCommand(DeletionReasonTag.TEMP_BUSY, "临时有事", 5)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_EMPTY_REQUEST");
  }

  @Test
  @DisplayName("删除是有效操作，会刷新空闲计时")
  void 删除刷新有效操作() {
    Todo todo = TodoFixtures.task().build();
    stubDelete(todo, null);

    service.delete(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoDeletionService.DeleteCommand(DeletionReasonTag.ADDED_BY_MISTAKE, "加错了这条", 5));

    verify(effectiveAction).record(TodoFixtures.USER_ID);
  }

  @Test
  @DisplayName("专注 Todo 的快照保留已累计专注时长与专注状态")
  void 专注快照保留时长() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.ABANDONED)
            .focusedMs(300_000L)
            .status(TodoStatus.IN_PROGRESS)
            .build();
    stubDelete(todo, null);

    service.delete(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoDeletionService.DeleteCommand(DeletionReasonTag.TEMP_BUSY, "临时有事情要走", 5));

    ArgumentCaptor<TodoRepository.Deletion> deletion =
        ArgumentCaptor.forClass(TodoRepository.Deletion.class);
    verify(todos).insertDeletion(deletion.capture());
    assertThat(deletion.getValue().progressSnapshotJson())
        .contains("\"focusedMs\":300000")
        .contains("\"focusState\":\"ABANDONED\"")
        .contains("\"targetPermille\":null");
  }

  private void stubDelete(Todo todo, String supervisorUserId) {
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(supervisions.primarySupervisorOf(TodoFixtures.USER_ID))
        .thenReturn(Optional.ofNullable(supervisorUserId));
    when(userTime.today(TodoFixtures.USER_ID)).thenReturn(LocalDate.of(2026, 9, 7));
    when(todos.countDeletionsOn(TodoFixtures.USER_ID, LocalDate.of(2026, 9, 7))).thenReturn(1);
  }

  private static IdGenerator fixedIdGenerator() {
    return () -> "deletion-1";
  }
}
