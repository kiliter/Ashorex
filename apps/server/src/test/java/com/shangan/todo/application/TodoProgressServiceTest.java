package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.TodoFixtures;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 进度上报服务：幂等、单调、达标裁决与课时累计。 */
@ExtendWith(MockitoExtension.class)
class TodoProgressServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

  @Mock private TodoRepository todos;
  @Mock private CourseRepository courses;
  @Mock private CatalogQueryService catalog;
  @Mock private TodoService todoService;
  @Mock private EffectiveActionRecorder effectiveAction;

  private TodoProgressService service;

  @BeforeEach
  void setUp() {
    service =
        new TodoProgressService(
            todos,
            courses,
            catalog,
            todoService,
            effectiveAction,
            sequentialIdGenerator(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("专注不能通过课程进度接口重复写入计时时长")
  void 专注只允许专用操作() {
    Todo todo = TodoFixtures.focus().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    assertThatThrownBy(
            () ->
                service.report(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoProgressService.ProgressReport(
                        1, NOW, "FOCUS_TICK", null, null, 0, 600_000, "FOREGROUND")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("TODO_PROGRESS_NOT_APPLICABLE");
    verify(todos, never()).insertProgressEvent(any());
  }

  @Test
  @DisplayName("课程上报不能注入专注时长污染统计")
  void 课程拒绝专注时长() {
    Todo todo = TodoFixtures.course().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    assertThatThrownBy(
            () ->
                service.report(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoProgressService.ProgressReport(
                        1, NOW, "PROGRESS", 1000L, null, 1000, 600_000, "FOREGROUND")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("TODO_PROGRESS_NOT_APPLICABLE");
    verify(todos, never()).insertProgressEvent(any());
  }

  @Test
  @DisplayName("课程上报不能借专注事件名伪造操作留痕")
  void 课程拒绝专注事件() {
    Todo todo = TodoFixtures.course().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    assertThatThrownBy(
            () ->
                service.report(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoProgressService.ProgressReport(
                        1, NOW, "FOCUS_FINISH", 1000L, null, 1000, 0, "FOREGROUND")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("TODO_PROGRESS_EVENT_INVALID");
    verify(todos, never()).insertProgressEvent(any());
  }

  @Test
  @DisplayName("课程达标但缺凭证时保留进度和时长，不提前完成")
  void 课程达标仍须凭证() {
    Todo todo = TodoFixtures.course().targetProgressPermille(500).requireEvidence(true).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));
    TodoProgressService.ProgressResult result =
        service.report(TodoFixtures.USER_ID, todo.id(), report(1, 50_000L, 15_000L, "FOREGROUND"));
    assertThat(result.positionMs()).isEqualTo(50_000L);
    assertThat(result.watchedMs()).isEqualTo(15_000L);
    assertThat(result.completed()).isFalse();
    verify(todoService, never()).snapshotCompletion(any());
    verify(todos).upsertWatchState(TodoFixtures.USER_ID, "resource-1", 50_000L, 0, 15_000L, 0, NOW);
  }

  @Test
  @DisplayName("同一 clientSeq 重放时直接返回当前状态，不写流水也不重复累计")
  void 重复上报幂等() {
    Todo todo = TodoFixtures.course().progressPositionMs(30_000L).watchedMs(30_000L).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 7L)).thenReturn(true);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));

    TodoProgressService.ProgressResult result =
        service.report(TodoFixtures.USER_ID, todo.id(), report(7L, 90_000L, 15_000L, "FOREGROUND"));

    assertThat(result.positionMs()).isEqualTo(30_000L);
    assertThat(result.watchedMs()).isEqualTo(30_000L);
    assertThat(result.progressPermille()).isEqualTo(300);
    verify(todos, never()).insertProgressEvent(any());
    verify(todos, never())
        .updateProgress(anyString(), anyLong(), anyInt(), anyLong(), anyString(), any());
    verifyNoInteractions(effectiveAction);
  }

  @Test
  @DisplayName("乱序重放：更小的位置不会让已记录的最远位置回退")
  void 乱序上报位置不回退() {
    Todo todo = TodoFixtures.course().progressPositionMs(60_000L).watchedMs(60_000L).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 3L)).thenReturn(false);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));

    TodoProgressService.ProgressResult result =
        service.report(TodoFixtures.USER_ID, todo.id(), report(3L, 20_000L, 5_000L, "FOREGROUND"));

    assertThat(result.positionMs()).isEqualTo(60_000L);
    verify(todos)
        .updateProgress(todo.id(), 60_000L, 0, 65_000L, TodoStatus.IN_PROGRESS.name(), NOW);
  }

  @Test
  @DisplayName("后台上报只推进位置，不累计观看时长")
  void 后台上报不累计时长() {
    Todo todo = TodoFixtures.course().progressPositionMs(10_000L).watchedMs(10_000L).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 4L)).thenReturn(false);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));

    TodoProgressService.ProgressResult result =
        service.report(TodoFixtures.USER_ID, todo.id(), report(4L, 40_000L, 30_000L, "BACKGROUND"));

    assertThat(result.positionMs()).isEqualTo(40_000L);
    assertThat(result.watchedMs()).isEqualTo(10_000L);

    ArgumentCaptor<TodoRepository.ProgressEvent> event =
        ArgumentCaptor.forClass(TodoRepository.ProgressEvent.class);
    verify(todos).insertProgressEvent(event.capture());
    assertThat(event.getValue().appState()).isEqualTo("BACKGROUND");
    assertThat(event.getValue().deltaWatchedMs()).isZero();
  }

  @Test
  @DisplayName("达到目标千分比时服务端直接判定完成")
  void 达标自动完成() {
    Todo todo =
        TodoFixtures.course().targetProgressPermille(500).progressPositionMs(40_000L).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 9L)).thenReturn(false);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));

    TodoProgressService.ProgressResult result =
        service.report(TodoFixtures.USER_ID, todo.id(), report(9L, 50_000L, 10_000L, "FOREGROUND"));

    assertThat(result.progressPermille()).isEqualTo(500);
    assertThat(result.status()).isEqualTo(TodoStatus.DONE);
    assertThat(result.completed()).isTrue();
    verify(todoService).snapshotCompletion(todo);
    verify(todos).upsertWatchState(TodoFixtures.USER_ID, "resource-1", 50_000L, 0, 10_000L, 1, NOW);
  }

  @Test
  @DisplayName("差一千分比未达标时保持进行中，且课时累计不计完成次数")
  void 未达标保持进行中() {
    Todo todo =
        TodoFixtures.course().targetProgressPermille(500).progressPositionMs(40_000L).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 10L)).thenReturn(false);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));

    TodoProgressService.ProgressResult result =
        service.report(TodoFixtures.USER_ID, todo.id(), report(10L, 49_900L, 9_900L, "FOREGROUND"));

    assertThat(result.progressPermille()).isEqualTo(499);
    assertThat(result.status()).isEqualTo(TodoStatus.IN_PROGRESS);
    assertThat(result.completed()).isFalse();
    verify(todos).upsertWatchState(TodoFixtures.USER_ID, "resource-1", 49_900L, 0, 9_900L, 0, NOW);
  }

  @Test
  @DisplayName("已完成的课程 Todo 继续上报仍累计时长，但不再重复计一次完成")
  void 完成后继续累计不重复计完成() {
    Todo todo =
        TodoFixtures.course()
            .targetProgressPermille(500)
            .status(TodoStatus.DONE)
            .progressPositionMs(50_000L)
            .watchedMs(50_000L)
            .build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 11L)).thenReturn(false);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));

    TodoProgressService.ProgressResult result =
        service.report(
            TodoFixtures.USER_ID, todo.id(), report(11L, 70_000L, 20_000L, "FOREGROUND"));

    assertThat(result.status()).isEqualTo(TodoStatus.DONE);
    assertThat(result.watchedMs()).isEqualTo(70_000L);
    verify(todos).upsertWatchState(TodoFixtures.USER_ID, "resource-1", 70_000L, 0, 20_000L, 0, NOW);
  }

  @Test
  @DisplayName("每次成功上报都刷新有效操作时间")
  void 成功上报刷新有效操作() {
    Todo todo = TodoFixtures.course().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 1L)).thenReturn(false);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));

    service.report(TodoFixtures.USER_ID, todo.id(), report(1L, 1_000L, 1_000L, "FOREGROUND"));

    verify(effectiveAction).record(TodoFixtures.USER_ID);
  }

  @Test
  @DisplayName("待办事项类型不接受进度上报")
  void 待办事项拒绝进度上报() {
    Todo todo = TodoFixtures.task().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    assertThatThrownBy(
            () ->
                service.report(
                    TodoFixtures.USER_ID, todo.id(), report(1L, 1_000L, 1_000L, "FOREGROUND")))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_PROGRESS_NOT_APPLICABLE");
    verify(todos, never()).insertProgressEvent(any());
  }

  @Test
  @DisplayName("未指定上报时刻时用服务端时钟兜底，流水按服务端时间落库")
  void 缺少上报时刻时用服务端时钟() {
    Todo todo = TodoFixtures.course().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 2L)).thenReturn(false);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.of(video(100_000L)));

    service.report(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoProgressService.ProgressReport(
            2L, null, null, 5_000L, null, 5_000L, 0L, "FOREGROUND"));

    ArgumentCaptor<TodoRepository.ProgressEvent> event =
        ArgumentCaptor.forClass(TodoRepository.ProgressEvent.class);
    verify(todos).insertProgressEvent(event.capture());
    assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
    assertThat(event.getValue().createdAt()).isEqualTo(NOW);
    assertThat(event.getValue().eventType()).isEqualTo("PROGRESS");
    assertThat(event.getValue().clientSeq()).isEqualTo(2L);
  }

  @Test
  @DisplayName("资源已下架读不到时长时不判定达标，只推进位置")
  void 资源缺失时不判定达标() {
    Todo todo = TodoFixtures.course().targetProgressPermille(500).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.progressEventExists(todo.id(), 5L)).thenReturn(false);
    when(courses.findResourceById("resource-1")).thenReturn(Optional.empty());

    TodoProgressService.ProgressResult result =
        service.report(TodoFixtures.USER_ID, todo.id(), report(5L, 999_000L, 1_000L, "FOREGROUND"));

    assertThat(result.progressPermille()).isZero();
    assertThat(result.status()).isEqualTo(TodoStatus.IN_PROGRESS);
    verify(todos)
        .updateProgress(eq(todo.id()), eq(999_000L), eq(0), eq(1_000L), eq("IN_PROGRESS"), eq(NOW));
  }

  @Test
  @DisplayName("归档课程的已完成待办不能先清零再进入不可播放状态")
  void 归档课程拒绝重新复习() {
    Todo todo = TodoFixtures.course().status(TodoStatus.DONE).build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.playbackSessions(java.util.List.of(todo.id())))
        .thenReturn(
            java.util.Map.of(todo.id(), new TodoRepository.PlaybackSession(0, 90_000, null)));
    when(catalog.requireVisibleResource("resource-1"))
        .thenThrow(
            new BusinessException(
                org.springframework.http.HttpStatus.CONFLICT, "RESOURCE_UNAVAILABLE", "所属课程已归档"));

    assertThatThrownBy(() -> service.restartReview(TodoFixtures.USER_ID, todo.id(), 0, "review-1"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("TODO_RESOURCE_UNAVAILABLE");
    verify(todos, never()).resetPlayback(anyString(), anyLong(), anyString(), any());
  }

  /** 旧客户端退出事件必须兼容排队重放，但不能把数据库不支持的值直接写入。 */
  @Test
  void legacyExitIsStoredAsPause() {
    Todo todo = TodoFixtures.course().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    service.report(
        TodoFixtures.USER_ID,
        todo.id(),
        new TodoProgressService.ProgressReport(
            99L, NOW, "EXIT", 1000L, null, 1000L, 0L, "FOREGROUND"));
    var event = ArgumentCaptor.forClass(TodoRepository.ProgressEvent.class);
    verify(todos).insertProgressEvent(event.capture());
    assertThat(event.getValue().eventType()).isEqualTo("PAUSE");
    assertThat(event.getValue().deltaWatchedMs()).isEqualTo(1000);
  }

  /** 非法事件在写流水之前返回业务错误，不让数据库 CHECK 约束变成 500。 */
  @Test
  void unknownEventIsRejectedBeforeWrite() {
    Todo todo = TodoFixtures.course().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    assertThatThrownBy(
            () ->
                service.report(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    new TodoProgressService.ProgressReport(
                        99L, NOW, "INVALID", 1000L, null, 1000L, 0L, "FOREGROUND")))
        .isInstanceOf(BusinessException.class)
        .hasMessage("不支持的进度事件类型");
    verify(todos, never()).insertProgressEvent(any());
    verifyNoInteractions(effectiveAction);
  }

  private static TodoProgressService.ProgressReport report(
      long clientSeq, long positionMs, long deltaWatchedMs, String appState) {
    return new TodoProgressService.ProgressReport(
        clientSeq, NOW, "PROGRESS", positionMs, null, deltaWatchedMs, 0L, appState);
  }

  private static LearningResource video(long durationMs) {
    return new LearningResource(
        "resource-1",
        "course-1",
        ResourceType.VIDEO,
        "第 1 讲",
        1,
        durationMs,
        null,
        "emby:1",
        "path-sha256-v1:abc",
        true,
        CatalogStatus.ACTIVE,
        null);
  }

  /** 顺序 ID，便于断言时无需关心具体 UUID。 */
  private static IdGenerator sequentialIdGenerator() {
    return new IdGenerator() {
      private int counter;

      @Override
      public String nextId() {
        return "id-" + (++counter);
      }
    };
  }
}
