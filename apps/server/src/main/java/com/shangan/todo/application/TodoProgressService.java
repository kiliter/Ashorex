package com.shangan.todo.application;

import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoPolicy;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 进度上报与完成判定。
 *
 * <p>幂等由 {@code (todo_id, client_seq)} 唯一约束保证；位置单调不回退；只有前台上报的时长增量才累计； 是否达标由服务端裁决，客户端不自行判断（见 Spec
 * 7.1）。
 */
@Service
public class TodoProgressService {

  private final TodoRepository todos;
  private final CourseRepository courses;
  private final TodoService todoService;
  private final EffectiveActionRecorder effectiveAction;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public TodoProgressService(
      TodoRepository todos,
      CourseRepository courses,
      TodoService todoService,
      EffectiveActionRecorder effectiveAction,
      IdGenerator idGenerator,
      Clock clock) {
    this.todos = todos;
    this.courses = courses;
    this.todoService = todoService;
    this.effectiveAction = effectiveAction;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  /** 处理一次进度上报；重复 clientSeq 直接返回当前状态且不重复计数。 */
  @Transactional
  public ProgressResult report(String userId, String todoId, ProgressReport report) {
    Todo todo = todoService.requireOwned(userId, todoId);
    if (todo.todoType() != TodoType.COURSE) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_PROGRESS_NOT_APPLICABLE", "只有课程接受进度上报；专注请使用计时操作接口");
    }
    if (todos.progressEventExists(todo.id(), report.clientSeq())) {
      return describe(todo);
    }
    // ADR-0036 专注时长由服务端专用状态操作生成，通用接口不能再写入第二份时长。
    if (report.deltaFocusedMs() != 0) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_PROGRESS_NOT_APPLICABLE", "专注时长请通过计时操作记录");
    }
    String eventType = normalizeEventType(report.eventType());
    Instant now = clock.instant();
    Instant occurredAt = report.occurredAt() == null ? now : report.occurredAt();
    boolean foreground = !"BACKGROUND".equalsIgnoreCase(report.appState());

    todos.insertProgressEvent(
        new TodoRepository.ProgressEvent(
            idGenerator.nextId(),
            todo.id(),
            userId,
            report.clientSeq(),
            eventType,
            report.positionMs(),
            report.positionPage(),
            foreground ? Math.max(0, report.deltaWatchedMs()) : 0,
            foreground ? Math.max(0, report.deltaFocusedMs()) : 0,
            foreground ? "FOREGROUND" : "BACKGROUND",
            occurredAt,
            now));

    long position = TodoPolicy.advancePosition(todo.progressPositionMs(), report.positionMs());
    int page = TodoPolicy.advancePage(todo.progressPage(), report.positionPage());
    long watched = TodoPolicy.accumulate(todo.watchedMs(), report.deltaWatchedMs(), foreground);

    Optional<LearningResource> resource =
        todo.resourceId() == null ? Optional.empty() : courses.findResourceById(todo.resourceId());
    int permille = resource.map(value -> value.progressPermille(position, page)).orElse(0);
    // 达标先保留进度，要求凭证但尚未上传时仍保持未完成，后续可上传并确认。
    boolean reached =
        todo.reachedTarget(permille)
            && (!todo.requireEvidence() || !todos.attachmentsOf(todo.id()).isEmpty());
    TodoStatus status =
        todo.done() ? TodoStatus.DONE : reached ? TodoStatus.DONE : TodoStatus.IN_PROGRESS;

    todos.updateProgress(todo.id(), position, page, watched, status.name(), now);
    long addedWatched = watched - todo.watchedMs();
    boolean newlyCompleted = status == TodoStatus.DONE && !todo.done();
    if (newlyCompleted) todoService.snapshotCompletion(todo);
    if (todo.resourceId() != null) {
      todos.upsertWatchState(
          userId,
          todo.resourceId(),
          position,
          page,
          addedWatched,
          newlyCompleted ? 1 : 0,
          occurredAt);
    }
    effectiveAction.record(userId);

    return new ProgressResult(
        status,
        position,
        page,
        watched,
        permille,
        todo.targetProgressPermille(),
        status == TodoStatus.DONE);
  }

  /** 退出按暂停落库，兼容旧客户端离线队列；其余事件必须符合冻结协议与数据库枚举。 */
  private String normalizeEventType(String value) {
    if (value == null) return "PROGRESS";
    if ("EXIT".equals(value)) return "PAUSE";
    if (java.util.Set.of("PROGRESS", "PAUSE", "RESUME", "COMPLETE").contains(value)) return value;
    throw new BusinessException(
        HttpStatus.BAD_REQUEST, "TODO_PROGRESS_EVENT_INVALID", "不支持的进度事件类型");
  }

  private ProgressResult describe(Todo todo) {
    Optional<LearningResource> resource =
        todo.resourceId() == null ? Optional.empty() : courses.findResourceById(todo.resourceId());
    int permille =
        resource
            .map(value -> value.progressPermille(todo.progressPositionMs(), todo.progressPage()))
            .orElse(0);
    return new ProgressResult(
        todo.status(),
        todo.progressPositionMs(),
        todo.progressPage(),
        todo.watchedMs(),
        permille,
        todo.targetProgressPermille(),
        todo.done());
  }

  /** 一次进度上报的入参。 */
  public record ProgressReport(
      long clientSeq,
      Instant occurredAt,
      String eventType,
      Long positionMs,
      Integer positionPage,
      long deltaWatchedMs,
      long deltaFocusedMs,
      String appState) {}

  /** 服务端裁决结果；客户端据此更新 UI。 */
  public record ProgressResult(
      TodoStatus status,
      long positionMs,
      int positionPage,
      long watchedMs,
      int progressPermille,
      Integer targetProgressPermille,
      boolean completed) {}
}
