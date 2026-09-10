package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 重置只影响当前 Todo；历史时长、DONE 和课时历史不能被复习回写覆盖。 */
class TodoReviewProgressTest {
  final TodoRepository todos = mock(TodoRepository.class);
  final CourseRepository courses = mock(CourseRepository.class);
  final TodoService owner = mock(TodoService.class);
  final EffectiveActionRecorder actions = mock(EffectiveActionRecorder.class);
  final Instant now = Instant.parse("2026-09-10T00:00:00Z");
  final TodoProgressService service =
      new TodoProgressService(
          todos, courses, owner, actions, () -> "event-id", Clock.fixed(now, ZoneOffset.UTC));

  @Test
  void resetPreservesCompletionAndDoesNotTouchCourseHistory() {
    var todo = TodoFixtures.course().status(TodoStatus.DONE).build();
    when(owner.requireOwned(todo.userId(), todo.id())).thenReturn(todo);
    when(courses.findResourceById(todo.resourceId()))
        .thenReturn(Optional.of(TodoFixtures.videoResource(60000)));
    when(todos.resetPlayback(todo.id(), 0, "request-1", now)).thenReturn(true);
    assertThat(service.restartReview(todo.userId(), todo.id(), 0, "request-1").epoch())
        .isEqualTo(1);
    verify(todos).resetPlayback(todo.id(), 0, "request-1", now);
    verify(todos, never()).updateProgress(any(), anyLong(), anyInt(), anyLong(), any(), any());
    verify(todos, never())
        .upsertWatchState(any(), any(), anyLong(), anyInt(), anyLong(), anyInt(), any());
  }

  @Test
  void sameRequestDoesNotClearProgressTwice() {
    var todo = TodoFixtures.course().status(TodoStatus.DONE).build();
    when(owner.requireOwned(todo.userId(), todo.id())).thenReturn(todo);
    when(todos.playbackSessions(List.of(todo.id())))
        .thenReturn(Map.of(todo.id(), new TodoRepository.PlaybackSession(1, 12000, "request-1")));
    assertThat(service.restartReview(todo.userId(), todo.id(), 0, "request-1").resumePositionMs())
        .isEqualTo(12000);
    verify(todos, never()).resetPlayback(any(), anyLong(), any(), any());
  }

  @Test
  void oldRoundAccumulatesTimeWithoutRestoringOldPosition() {
    var todo = TodoFixtures.course().status(TodoStatus.DONE).build();
    when(owner.requireOwned(todo.userId(), todo.id())).thenReturn(todo);
    when(todos.playbackSessions(List.of(todo.id())))
        .thenReturn(Map.of(todo.id(), new TodoRepository.PlaybackSession(1, 0, "request-1")));
    var result =
        service.report(
            todo.userId(),
            todo.id(),
            new TodoProgressService.ProgressReport(
                1, now, "PROGRESS", 59000L, null, 1000, 0, "FOREGROUND", 0));
    assertThat(result.positionMs()).isEqualTo(todo.progressPositionMs());
    assertThat(result.status()).isEqualTo(TodoStatus.DONE);
    assertThat(result.watchedMs()).isEqualTo(todo.watchedMs() + 1000);
    verify(todos, never()).rememberPlayback(any(), anyLong(), anyLong(), anyLong());
    verify(todos)
        .upsertWatchState(
            eq(todo.userId()),
            eq(todo.resourceId()),
            anyLong(),
            anyInt(),
            eq(1000L),
            eq(0),
            eq(now));
  }
}
