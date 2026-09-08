package com.shangan.todo.application;

import com.shangan.common.api.BusinessException;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 专注计时状态机。
 *
 * <p>{@code FINISHED} 判定完成；{@code ABANDONED} 保持未完成但保留已累计专注时长。 同一用户同一时刻只允许一个 {@code RUNNING}
 * 专注（由部分唯一索引兜底）。
 */
@Service
public class FocusService {

  private final TodoRepository todos;
  private final TodoService todoService;
  private final EffectiveActionRecorder effectiveAction;
  private final Clock clock;

  public FocusService(
      TodoRepository todos,
      TodoService todoService,
      EffectiveActionRecorder effectiveAction,
      Clock clock) {
    this.todos = todos;
    this.todoService = todoService;
    this.effectiveAction = effectiveAction;
    this.clock = clock;
  }

  /** 状态与流水原子提交；相同请求重放返回当前事实，避免重复累计。 */
  @Transactional
  public Todo execute(String userId, String todoId, String action, String requestId) {
    Todo before = requireFocus(userId, todoId);
    String normalized = action.toLowerCase(java.util.Locale.ROOT);
    String key = requestId == null ? java.util.UUID.randomUUID().toString() : requestId;
    if (key.isBlank() || key.length() > 100) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "FOCUS_REQUEST_INVALID", "操作标识无效，请重新操作");
    }
    var previous = todos.focusRequestAction(todoId, key);
    if (previous.isPresent()) {
      if (!previous.get().equals(normalized)) {
        throw new BusinessException(HttpStatus.CONFLICT, "FOCUS_REQUEST_CONFLICT", "该操作已处理，请刷新后重试");
      }
      return before;
    }
    Todo after =
        switch (normalized) {
          case "start" -> start(userId, todoId);
          case "pause" -> pause(userId, todoId);
          case "resume" -> resume(userId, todoId);
          case "stop" -> stop(userId, todoId);
          case "finish" -> finish(userId, todoId);
          case "abandon" -> abandon(userId, todoId);
          default ->
              throw new BusinessException(
                  HttpStatus.BAD_REQUEST, "FOCUS_ACTION_UNKNOWN", "未知的专注操作");
        };
    todos.recordFocusAction(before, after, normalized, key, clock.instant());
    return after;
  }

  /** 停止当前一段，保留累计时长，之后从零开始新一轮。 */
  @Transactional
  public Todo stop(String userId, String todoId) {
    Todo todo = requireFocus(userId, todoId);
    ensureTransition(todo, FocusState.STOPPED);
    Instant now = clock.instant();
    todos.updateFocus(
        todo.id(),
        FocusState.STOPPED.name(),
        null,
        accumulated(todo, now),
        TodoStatus.IN_PROGRESS.name(),
        null,
        now);
    effectiveAction.record(userId);
    return todoService.requireOwned(userId, todoId);
  }

  /** 从未开始或已停止开始计时，已跳过的专注不能重新运行。 */
  @Transactional
  public Todo start(String userId, String todoId) {
    Todo todo = requireFocus(userId, todoId);
    ensureTransition(todo, FocusState.RUNNING);
    todos
        .findRunningFocus(userId)
        .filter(running -> !running.id().equals(todo.id()))
        .ifPresent(
            running -> {
              throw new BusinessException(
                  HttpStatus.CONFLICT, "FOCUS_ALREADY_RUNNING", "已有一个专注在进行中");
            });
    Instant now = clock.instant();
    todos.updateFocus(
        todo.id(),
        FocusState.RUNNING.name(),
        now,
        todo.focusedMs(),
        TodoStatus.IN_PROGRESS.name(),
        null,
        now);
    effectiveAction.record(userId);
    return todoService.requireOwned(userId, todoId);
  }

  /** 暂停：把本段已经过的时间累计进去，并清空开始时间。 */
  @Transactional
  public Todo pause(String userId, String todoId) {
    Todo todo = requireFocus(userId, todoId);
    ensureTransition(todo, FocusState.PAUSED);
    Instant now = clock.instant();
    todos.updateFocus(
        todo.id(),
        FocusState.PAUSED.name(),
        null,
        accumulated(todo, now),
        TodoStatus.IN_PROGRESS.name(),
        null,
        now);
    effectiveAction.record(userId);
    return todoService.requireOwned(userId, todoId);
  }

  /** 继续暂停的专注，同样校验用户当前是否已有其他计时。 */
  @Transactional
  public Todo resume(String userId, String todoId) {
    Todo todo = requireFocus(userId, todoId);
    if (todo.done() || todo.focusState() != FocusState.PAUSED) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "FOCUS_ILLEGAL_TRANSITION", "专注状态已更新，请刷新后再操作");
    }
    todos
        .findRunningFocus(userId)
        .filter(running -> !running.id().equals(todoId))
        .ifPresent(
            running -> {
              throw new BusinessException(
                  HttpStatus.CONFLICT, "FOCUS_ALREADY_RUNNING", "已有一个专注在进行中");
            });
    Instant now = clock.instant();
    todos.updateFocus(
        todo.id(),
        FocusState.RUNNING.name(),
        now,
        todo.focusedMs(),
        TodoStatus.IN_PROGRESS.name(),
        null,
        now);
    effectiveAction.record(userId);
    return todoService.requireOwned(userId, todoId);
  }

  /** 倒计时自然结束：判定完成；要求拍照时必须已有附件。 */
  @Transactional
  public Todo finish(String userId, String todoId) {
    Todo todo = requireFocus(userId, todoId);
    ensureTransition(todo, FocusState.FINISHED);
    if (accumulated(todo, clock.instant()) - todo.focusAttemptBaseMs()
        < todo.plannedSeconds() * 1000L) {
      throw new BusinessException(HttpStatus.CONFLICT, "FOCUS_NOT_FINISHED", "本次倒计时尚未结束，请继续专注");
    }
    if (todo.requireEvidence() && todos.attachmentsOf(todo.id()).isEmpty()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_EVIDENCE_REQUIRED", "该专注要求先上传完成凭证");
    }
    Instant now = clock.instant();
    todos.updateFocus(
        todo.id(),
        FocusState.FINISHED.name(),
        null,
        accumulated(todo, now),
        TodoStatus.DONE.name(),
        now,
        now);
    todoService.snapshotCompletion(todo);
    effectiveAction.record(userId);
    return todoService.requireOwned(userId, todoId);
  }

  /** 跳过：Todo 保持未完成，已累计专注时长保留并进入统计。 */
  @Transactional
  public Todo abandon(String userId, String todoId) {
    Todo todo = requireFocus(userId, todoId);
    ensureTransition(todo, FocusState.ABANDONED);
    Instant now = clock.instant();
    todos.updateFocus(
        todo.id(),
        FocusState.ABANDONED.name(),
        null,
        accumulated(todo, now),
        TodoStatus.IN_PROGRESS.name(),
        null,
        now);
    effectiveAction.record(userId);
    return todoService.requireOwned(userId, todoId);
  }

  private long accumulated(Todo todo, Instant now) {
    if (todo.focusState() != FocusState.RUNNING || todo.focusStartedAt() == null) {
      return todo.focusedMs();
    }
    long elapsed = Duration.between(todo.focusStartedAt(), now).toMillis();
    long remaining = Math.max(0, todo.plannedSeconds() * 1000L - todo.focusAttemptMs());
    return todo.focusedMs() + Math.min(remaining, Math.max(0, elapsed));
  }

  private Todo requireFocus(String userId, String todoId) {
    Todo todo = todoService.requireOwned(userId, todoId);
    if (todo.todoType() != TodoType.FOCUS) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "FOCUS_NOT_APPLICABLE", "该待办不是专注计时");
    }
    return todo;
  }

  private void ensureTransition(Todo todo, FocusState target) {
    if (todo.done() || !todo.focusState().canTransitionTo(target)) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "FOCUS_ILLEGAL_TRANSITION", "专注状态已更新，请刷新后再操作");
    }
  }
}
