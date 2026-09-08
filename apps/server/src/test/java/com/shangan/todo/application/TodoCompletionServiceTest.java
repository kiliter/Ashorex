package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 完成与补记完成：凭证必填、补记只能对历史日期且必填备注。 */
@ExtendWith(MockitoExtension.class)
class TodoCompletionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
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

  @Mock private FocusService focus;
  @Mock private TodoRepository todos;
  @Mock private TodoService todoService;
  @Mock private UserTimeService userTime;
  @Mock private EffectiveActionRecorder effectiveAction;

  private TodoCompletionService service;

  @BeforeEach
  void setUp() {
    service =
        new TodoCompletionService(
            todos, todoService, userTime, effectiveAction, Clock.fixed(NOW, ZoneOffset.UTC), focus);
  }

  @Test
  @DisplayName("手动完成运行专注前先停止计时，释放同用户运行槽位并保存本段时长")
  void 手动完成先停止运行专注() {
    Todo todo = TodoFixtures.focus().focusState(com.shangan.todo.domain.FocusState.RUNNING).build();
    stubTodo(todo);
    service.complete(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoCompletionService.CompleteCommand(null, null, false));
    var order = org.mockito.Mockito.inOrder(focus, todos, todoService);
    order.verify(focus).execute(TodoFixtures.USER_ID, todo.id(), "stop", null);
    order.verify(todos).updateStatus(todo.id(), "DONE", NOW, NOW);
    order.verify(todoService).snapshotCompletion(todo);
  }

  @Test
  @DisplayName("手动勾选完成：写入 DONE 与完成时间，并刷新有效操作")
  void 手动勾选完成() {
    Todo todo = TodoFixtures.task().build();
    stubTodo(todo);

    service.complete(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoCompletionService.CompleteCommand(null, null, null));

    verify(todos).updateStatus(todo.id(), TodoStatus.DONE.name(), NOW, NOW);
    verify(todos, never()).markBackfilled(anyString(), anyString(), any(), any());
    verify(effectiveAction).record(TodoFixtures.USER_ID);
  }

  @Test
  @DisplayName("要求凭证但没有附件时拒绝完成")
  void 缺少凭证拒绝完成() {
    Todo todo = TodoFixtures.task().requireEvidence(true).build();
    stubTodo(todo);
    when(todos.attachmentsOf(todo.id())).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.complete(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoCompletionService.CompleteCommand(null, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_EVIDENCE_REQUIRED");
    verify(todos, never()).updateStatus(anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("补记完成必须是历史日期：对今天补记返回 TODO_BACKFILL_NOT_HISTORY")
  void 当天不允许补记() {
    Todo todo = TodoFixtures.task().localDate(LocalDate.of(2026, 9, 7)).build();
    stubTodo(todo);

    assertThatThrownBy(
            () ->
                service.complete(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoCompletionService.CompleteCommand("昨天忘记勾了", null, true)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_BACKFILL_NOT_HISTORY");
  }

  @Test
  @DisplayName("补记完成必须填写备注：备注过短返回 TODO_BACKFILL_NOTE_REQUIRED")
  void 补记必须填备注() {
    Todo todo = TodoFixtures.task().localDate(LocalDate.of(2026, 9, 5)).build();
    stubTodo(todo);

    assertThatThrownBy(
            () ->
                service.complete(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoCompletionService.CompleteCommand("好", null, true)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_BACKFILL_NOTE_REQUIRED");
  }

  @Test
  @DisplayName("对历史日期补记完成：走 markBackfilled 而不是普通完成，便于统计单列")
  void 历史日期补记走单独写入() {
    Todo todo = TodoFixtures.task().localDate(LocalDate.of(2026, 9, 5)).build();
    stubTodo(todo);

    service.complete(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoCompletionService.CompleteCommand("当天做完了但忘记勾选", List.of(NoteTag.NOTED), true));

    verify(todos).updateNote(todo.id(), "当天做完了但忘记勾选", NOW);
    verify(todos).replaceNoteTags(todo.id(), Set.of(NoteTag.NOTED));
    verify(todos).markBackfilled(todo.id(), "当天做完了但忘记勾选", NOW, NOW);
    verify(todos, never()).updateStatus(anyString(), anyString(), any(), any());
    // 补记不产生任何时长增量，因此不会向课时累计写入观看毫秒。
    verify(todos, never())
        .upsertWatchState(
            anyString(), anyString(), anyLong(), anyInt(), anyLong(), anyInt(), any());
  }

  @Test
  @DisplayName("课程 Todo 完成时同步课时累计的完成次数，但不追加观看时长")
  void 课程完成只累计完成次数() {
    Todo todo = TodoFixtures.course().progressPositionMs(50_000L).watchedMs(50_000L).build();
    stubTodo(todo);

    service.complete(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoCompletionService.CompleteCommand(null, null, null));

    verify(todos).upsertWatchState(TodoFixtures.USER_ID, "resource-1", 50_000L, 0, 0L, 1, NOW);
  }

  @Test
  @DisplayName("已完成的待办重复完成时保持幂等，不再写库")
  void 重复完成保持幂等() {
    Todo todo = TodoFixtures.task().status(TodoStatus.DONE).build();
    stubTodo(todo);

    service.complete(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoCompletionService.CompleteCommand(null, null, null));

    verify(todos, never()).updateStatus(anyString(), anyString(), any(), any());
    verify(todos, never()).markBackfilled(anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("仅回填备注与标签不改变完成状态")
  void 只回填备注不改状态() {
    Todo todo = TodoFixtures.task().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    service.annotate(TodoFixtures.USER_ID, todo.id(), "  这节需要重看  ", List.of(NoteTag.NEED_REVIEW));

    verify(todos).updateNote(todo.id(), "这节需要重看", NOW);
    verify(todos).replaceNoteTags(todo.id(), Set.of(NoteTag.NEED_REVIEW));
    verify(todos, never()).updateStatus(anyString(), anyString(), any(), any());
  }

  private void stubTodo(Todo todo) {
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(userTime.requireUser(TodoFixtures.USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(LocalDate.of(2026, 9, 7));
  }
}
