package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.*;
import com.shangan.identity.infrastructure.UserRepository;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 日视图保持今日计划分母独立，历史累计时长不能进入当日还债增量。 */
class TodoRepaymentViewTest {
  /** 用明确 UTC 边界覆盖跨 UTC 日期、夏令时的 23 小时和 25 小时日。 */
  @ParameterizedTest
  @CsvSource({
    "Asia/Shanghai,2026-09-10,2026-09-09T16:00:00Z,2026-09-10T16:00:00Z",
    "America/New_York,2026-03-08,2026-03-08T05:00:00Z,2026-03-09T04:00:00Z",
    "America/New_York,2026-11-01,2026-11-01T04:00:00Z,2026-11-02T05:00:00Z"
  })
  void 删除数量按所查日期的用户时区归集(String zone, LocalDate date, Instant from, Instant to) {
    var repos = mock(TodoRepository.class);
    var users = mock(UserRepository.class);
    var user =
        new User(
            "user-1",
            "demo",
            "unused",
            "测试",
            zone,
            UserStatus.ACTIVE,
            null,
            Set.of(UserRole.LEARNER));
    when(users.findById("user-1")).thenReturn(Optional.of(user));
    var time =
        new UserTimeService(
            users, Clock.fixed(Instant.parse("2026-09-10T02:00:00Z"), ZoneOffset.UTC));
    when(repos.countDeletionsBetween("user-1", from, to)).thenReturn(3);
    var view =
        new TodoViewService(repos, mock(CourseRepository.class), time)
            .day("user-1", date.toString());
    assertThat(view.deletionCount()).isEqualTo(3);
    verify(repos).countDeletionsBetween("user-1", from, to);
  }

  @Test
  void 历史完成和未完成独立展示且保留计划日期() {
    var repos = mock(TodoRepository.class);
    var users = mock(UserRepository.class);
    var user =
        new User(
            "user-1",
            "demo",
            "hash",
            "测试",
            "Asia/Shanghai",
            UserStatus.ACTIVE,
            null,
            Set.of(UserRole.LEARNER));
    when(users.findById("user-1")).thenReturn(Optional.of(user));
    var time =
        new UserTimeService(
            users, Clock.fixed(Instant.parse("2026-09-09T02:00:00Z"), ZoneOffset.UTC));
    var today = time.today(user);
    var from = time.startOfDay(user, today);
    var to = time.endOfDayExclusive(user, today);
    var pending =
        TodoFixtures.task().id("old").localDate(today.minusDays(1)).watchedMs(900000).build();
    var done =
        TodoFixtures.task()
            .id("done")
            .localDate(today.minusDays(2))
            .status(TodoStatus.DONE)
            .build();
    when(repos.findByUserAndDate("user-1", today))
        .thenReturn(List.of(TodoFixtures.task().localDate(today).build()));
    when(repos.findRepaymentTodos("user-1", today, from, to)).thenReturn(List.of(pending, done));
    when(repos.aggregateDurations("user-1", from, to))
        .thenReturn(
            List.of(
                new TodoRepository.DurationBucket(
                    from.plusSeconds(60), 60000, 0, today.minusDays(1))));
    when(repos.findCompletions("user-1", from, to))
        .thenReturn(
            List.of(
                new TodoRepository.CompletionBucket(from.plusSeconds(120), today.minusDays(2))));
    var view = new TodoViewService(repos, mock(CourseRepository.class), time).day("user-1", null);
    assertThat(view.todos()).hasSize(1);
    assertThat(view.totals().total()).isEqualTo(1);
    assertThat(view.totals().done()).isZero();
    assertThat(view.repaymentTodos())
        .extracting(TodoViewService.TodoView::localDate)
        .containsExactly(today.minusDays(1), today.minusDays(2));
    assertThat(view.repayment()).isEqualTo(new RepaymentTotals(1, 60000, 0));
  }
}
