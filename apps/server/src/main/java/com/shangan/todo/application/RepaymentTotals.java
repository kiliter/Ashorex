package com.shangan.todo.application;

import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** 实际发生窗口内的历史任务增量；不使用 Todo 累计时长或修改计划日期。 */
public record RepaymentTotals(int done, long watchedMs, long focusedMs) {
  public static final RepaymentTotals EMPTY = new RepaymentTotals(0, 0, 0);

  /** 日期转换由 UserTimeService 提供，跨日以账号时区为准。 */
  public static RepaymentTotals summarize(
      List<TodoRepository.DurationBucket> events,
      List<TodoRepository.CompletionBucket> completions,
      Function<Instant, LocalDate> localDate) {
    long watched = 0;
    long focused = 0;
    for (var event : events) {
      if (event.plannedDate() != null
          && event.plannedDate().isBefore(localDate.apply(event.occurredAt()))) {
        watched += event.watchedMs();
        focused += event.focusedMs();
      }
    }
    int done =
        (int)
            completions.stream()
                .filter(item -> item.plannedDate().isBefore(localDate.apply(item.completedAt())))
                .count();
    return new RepaymentTotals(done, watched, focused);
  }
}
