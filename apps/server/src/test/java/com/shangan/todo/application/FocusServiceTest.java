package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 专注服务：两种终态、时长累计与并发 RUNNING 拒绝。 */
@ExtendWith(MockitoExtension.class)
class FocusServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T10:30:00Z");

  @Mock private TodoRepository todos;
  @Mock private TodoService todoService;
  @Mock private EffectiveActionRecorder effectiveAction;

  private FocusService service;

  @BeforeEach
  void setUp() {
    service =
        new FocusService(todos, todoService, effectiveAction, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("手动完成后不能通过旧专注状态重新打开待办")
  void 已完成专注不可回退() {
    Todo todo = TodoFixtures.focus().status(TodoStatus.DONE).focusState(FocusState.PAUSED).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    assertThatThrownBy(() -> service.resume(TodoFixtures.USER_ID, todo.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("FOCUS_ILLEGAL_TRANSITION");
    assertThatThrownBy(() -> service.start(TodoFixtures.USER_ID, todo.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("FOCUS_ILLEGAL_TRANSITION");
    verify(todos, never())
        .updateFocus(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());
  }

  @Test
  @DisplayName("开始专注：写入 RUNNING 与开始时间，Todo 转为进行中")
  void 开始专注() {
    Todo todo = TodoFixtures.focus().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.findRunningFocus(TodoFixtures.USER_ID)).thenReturn(Optional.empty());

    service.start(TodoFixtures.USER_ID, todo.id());

    verify(todos)
        .updateFocus(todo.id(), "RUNNING", NOW, 0L, TodoStatus.IN_PROGRESS.name(), null, NOW);
    verify(effectiveAction).record(TodoFixtures.USER_ID);
  }

  @Test
  @DisplayName("同一用户已有 RUNNING 专注时拒绝再开始，错误码 FOCUS_ALREADY_RUNNING")
  void 并发运行专注被拒绝() {
    Todo todo = TodoFixtures.focus().id("todo-a").build();
    Todo running = TodoFixtures.focus().id("todo-b").focusState(FocusState.RUNNING).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.findRunningFocus(TodoFixtures.USER_ID)).thenReturn(Optional.of(running));

    assertThatThrownBy(() -> service.start(TodoFixtures.USER_ID, todo.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("FOCUS_ALREADY_RUNNING");
    verify(todos, never())
        .updateFocus(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());
  }

  @Test
  @DisplayName("暂停：把本段已经过时间累计进 focusedMs 并清空开始时间")
  void 暂停累计本段时长() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.RUNNING)
            .focusStartedAt(NOW.minusSeconds(300))
            .focusedMs(120_000L)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    service.pause(TodoFixtures.USER_ID, todo.id());

    verify(todos)
        .updateFocus(todo.id(), "PAUSED", null, 420_000L, TodoStatus.IN_PROGRESS.name(), null, NOW);
  }

  @Test
  @DisplayName("恢复：保留已累计时长，用当前时刻作为新的开始时间")
  void 恢复不改变已累计时长() {
    Todo todo = TodoFixtures.focus().focusState(FocusState.PAUSED).focusedMs(420_000L).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    service.resume(TodoFixtures.USER_ID, todo.id());

    verify(todos)
        .updateFocus(todo.id(), "RUNNING", NOW, 420_000L, TodoStatus.IN_PROGRESS.name(), null, NOW);
  }

  @Test
  @DisplayName("倒计时结束：FINISHED 判定完成并写完成时间")
  void 完成判定为已完成() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.RUNNING)
            .focusStartedAt(NOW.minusSeconds(1_500))
            .focusedMs(0L)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    service.finish(TodoFixtures.USER_ID, todo.id());
    verify(todoService).snapshotCompletion(todo);

    verify(todos)
        .updateFocus(todo.id(), "FINISHED", null, 1_500_000L, TodoStatus.DONE.name(), NOW, NOW);
  }

  @Test
  @DisplayName("提前放弃：ABANDONED 保持未完成，但已累计专注时长完整保留")
  void 放弃保持未完成并保留时长() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.RUNNING)
            .focusStartedAt(NOW.minusSeconds(200))
            .focusedMs(60_000L)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    service.abandon(TodoFixtures.USER_ID, todo.id());

    verify(todos)
        .updateFocus(
            todo.id(), "ABANDONED", null, 260_000L, TodoStatus.IN_PROGRESS.name(), null, NOW);
  }

  @Test
  @DisplayName("终态专注再次操作返回 FOCUS_ILLEGAL_TRANSITION")
  void 终态不允许再转移() {
    Todo finished = TodoFixtures.focus().focusState(FocusState.FINISHED).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, finished.id())).thenReturn(finished);

    assertThatThrownBy(() -> service.start(TodoFixtures.USER_ID, finished.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("FOCUS_ILLEGAL_TRANSITION");
    verifyNoInteractions(effectiveAction);
  }

  @Test
  @DisplayName("空闲专注直接暂停也属于非法转移")
  void 空闲直接暂停非法() {
    Todo idle = TodoFixtures.focus().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, idle.id())).thenReturn(idle);

    assertThatThrownBy(() -> service.pause(TodoFixtures.USER_ID, idle.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("FOCUS_ILLEGAL_TRANSITION");
  }

  @Test
  @DisplayName("要求凭证的专注在无附件时不能完成")
  void 要求凭证时无附件拒绝完成() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.RUNNING)
            .focusStartedAt(NOW.minusSeconds(1500))
            .requireEvidence(true)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.attachmentsOf(todo.id())).thenReturn(List.of());

    assertThatThrownBy(() -> service.finish(TodoFixtures.USER_ID, todo.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_EVIDENCE_REQUIRED");
    verify(todos, never())
        .updateFocus(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());
  }

  @Test
  @DisplayName("要求凭证且已有附件时可以完成")
  void 要求凭证且有附件可完成() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.RUNNING)
            .focusStartedAt(NOW.minusSeconds(1500))
            .requireEvidence(true)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.attachmentsOf(todo.id()))
        .thenReturn(
            List.of(
                new TodoRepository.Attachment(
                    "attachment-1",
                    todo.id(),
                    TodoFixtures.USER_ID,
                    "user-1/a.png",
                    "a.png",
                    "image/png",
                    1_024L,
                    "sha",
                    0,
                    NOW)));

    service.finish(TodoFixtures.USER_ID, todo.id());

    verify(todos)
        .updateFocus(todo.id(), "FINISHED", null, 1_500_000L, TodoStatus.DONE.name(), NOW, NOW);
  }

  @Test
  @DisplayName("非专注类型的待办不能走专注接口")
  void 非专注类型拒绝() {
    Todo task = TodoFixtures.task().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, task.id())).thenReturn(task);

    assertThatThrownBy(() -> service.start(TodoFixtures.USER_ID, task.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("FOCUS_NOT_APPLICABLE");
  }

  @Test
  @DisplayName("暂停态放弃时不再叠加时间，只保留已累计时长")
  void 暂停态放弃不再叠加() {
    Todo todo = TodoFixtures.focus().focusState(FocusState.PAUSED).focusedMs(300_000L).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    service.abandon(TodoFixtures.USER_ID, todo.id());

    verify(todos)
        .updateFocus(
            todo.id(), "ABANDONED", null, 300_000L, TodoStatus.IN_PROGRESS.name(), null, NOW);
  }

  @Test
  @DisplayName("停止保留实际累计时长，仍为未完成")
  void 停止保留时长() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.RUNNING)
            .focusStartedAt(NOW.minusSeconds(20))
            .focusedMs(300_000)
            .focusAttemptBaseMs(300_000)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    service.stop(TodoFixtures.USER_ID, todo.id());
    verify(todos).updateFocus(todo.id(), "STOPPED", null, 320_000L, "IN_PROGRESS", null, NOW);
  }

  @Test
  @DisplayName("历史累计超过目标也不能抵扣新一轮")
  void 新一轮不能拼接旧时长() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.RUNNING)
            .focusStartedAt(NOW.minusSeconds(20))
            .focusedMs(2_000_000)
            .focusAttemptBaseMs(2_000_000)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    assertThatThrownBy(() -> service.finish(TodoFixtures.USER_ID, todo.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("FOCUS_NOT_FINISHED");
    verify(todos, never())
        .updateFocus(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());
  }

  @Test
  @DisplayName("新一轮达到目标时保留历史累计并判定完成")
  void 新一轮独立达标() {
    Todo todo =
        TodoFixtures.focus()
            .focusState(FocusState.RUNNING)
            .focusStartedAt(NOW.minusSeconds(1500))
            .focusedMs(300_000)
            .focusAttemptBaseMs(300_000)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    service.finish(TodoFixtures.USER_ID, todo.id());
    verify(todos).updateFocus(todo.id(), "FINISHED", null, 1_800_000L, "DONE", NOW, NOW);
  }

  @Test
  @DisplayName("同一操作重放直接返回事实，不重复计时和写流水")
  void 请求重放不重复计时() {
    Todo todo = TodoFixtures.focus().focusState(FocusState.STOPPED).focusedMs(300_000).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.focusRequestAction(todo.id(), "request-1")).thenReturn(Optional.of("stop"));
    assertThat(service.execute(TodoFixtures.USER_ID, todo.id(), "stop", "request-1"))
        .isEqualTo(todo);
    verify(todos, never()).recordFocusAction(any(), any(), anyString(), anyString(), any());
    verify(todos, never())
        .updateFocus(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());
  }

  @Test
  @DisplayName("跳过写入含前后状态的操作流水")
  void 跳过流水记录() {
    Todo before = TodoFixtures.focus().focusState(FocusState.PAUSED).focusedMs(300_000).build();
    Todo after = TodoFixtures.focus().focusState(FocusState.ABANDONED).focusedMs(300_000).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, before.id()))
        .thenReturn(before, before, after);
    when(todos.focusRequestAction(before.id(), "request-1")).thenReturn(Optional.empty());
    assertThat(service.execute(TodoFixtures.USER_ID, before.id(), "abandon", "request-1"))
        .isEqualTo(after);
    verify(todos).recordFocusAction(before, after, "abandon", "request-1", NOW);
  }

  @Test
  @DisplayName("同一条专注重复开始不视为并发冲突")
  void 同一条专注不算并发冲突() {
    Todo todo = TodoFixtures.focus().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.findRunningFocus(TodoFixtures.USER_ID)).thenReturn(Optional.of(todo));

    service.start(TodoFixtures.USER_ID, todo.id());

    assertThat(todo.focusState()).isEqualTo(FocusState.IDLE);
    verify(todos)
        .updateFocus(todo.id(), "RUNNING", NOW, 0L, TodoStatus.IN_PROGRESS.name(), null, NOW);
  }
}
