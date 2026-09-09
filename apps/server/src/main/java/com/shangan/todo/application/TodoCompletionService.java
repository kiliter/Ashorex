package com.shangan.todo.application;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 统一完成入口；历史项保留原日期，要求凭证时必须先有附件。 */
@Service
public class TodoCompletionService {

  private final TodoRepository todos;
  private final TodoService todoService;
  private final UserTimeService userTime;
  private final EffectiveActionRecorder effectiveAction;
  private final Clock clock;
  private final FocusService focus;

  public TodoCompletionService(
      TodoRepository todos,
      TodoService todoService,
      UserTimeService userTime,
      EffectiveActionRecorder effectiveAction,
      Clock clock,
      FocusService focus) {
    this.todos = todos;
    this.todoService = todoService;
    this.userTime = userTime;
    this.effectiveAction = effectiveAction;
    this.clock = clock;
    this.focus = focus;
  }

  /** 正常标记完成；同时可回填备注与一键标签，旧补记请求显式拒绝。 */
  @Transactional
  public Todo complete(String userId, String todoId, CompleteCommand command) {
    Todo todo = todoService.requireOwned(userId, todoId);
    userTime.requireUser(userId);
    // 旧客户端不能再补记：历史待办通过正常执行在实际完成日记为还债。
    if (Boolean.TRUE.equals(command.backfill())) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_BACKFILL_REMOVED", "补记已取消，请直接完成历史待办");
    }
    if (todo.requireEvidence() && todos.attachmentsOf(todo.id()).isEmpty()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_EVIDENCE_REQUIRED", "该待办要求先上传完成凭证");
    }
    if (todo.done()) {
      return todo;
    }

    // 手动完成仍须结束运行中的专注并落下实际时长，释放同用户唯一运行槽位。
    if (todo.todoType() == com.shangan.todo.domain.TodoType.FOCUS
        && (todo.focusState() == com.shangan.todo.domain.FocusState.RUNNING
            || todo.focusState() == com.shangan.todo.domain.FocusState.PAUSED)) {
      focus.execute(userId, todoId, "stop", null);
    }
    Instant now = clock.instant();
    if (command.note() != null) {
      todos.updateNote(todo.id(), command.note().trim(), now);
    }
    if (command.noteTags() != null) {
      todos.replaceNoteTags(todo.id(), normalizeTags(command.noteTags()));
    }
    todos.updateStatus(todo.id(), TodoStatus.DONE.name(), now, now);
    todoService.snapshotCompletion(todo);
    if (todo.resourceId() != null) {
      todos.upsertWatchState(
          userId, todo.resourceId(), todo.progressPositionMs(), todo.progressPage(), 0L, 1, now);
    }
    effectiveAction.record(userId);
    return todoService.requireOwned(userId, todoId);
  }

  /** 仅回填备注与标签，不改变完成状态。 */
  @Transactional
  public Todo annotate(String userId, String todoId, String note, List<NoteTag> tags) {
    Todo todo = todoService.requireOwned(userId, todoId);
    Instant now = clock.instant();
    if (note != null) {
      todos.updateNote(todo.id(), note.trim(), now);
    }
    if (tags != null) {
      todos.replaceNoteTags(todo.id(), normalizeTags(tags));
    }
    effectiveAction.record(userId);
    return todoService.requireOwned(userId, todoId);
  }

  private Set<NoteTag> normalizeTags(List<NoteTag> tags) {
    return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
  }

  /** 完成命令；{@code backfill} 为真时表示对历史日期补记。 */
  public record CompleteCommand(String note, List<NoteTag> noteTags, Boolean backfill) {}
}
