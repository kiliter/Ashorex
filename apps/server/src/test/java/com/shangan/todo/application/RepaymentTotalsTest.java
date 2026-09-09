package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 还债只累计窗口内的实际增量，按用户时区和事件计划日期快照分类。 */
class RepaymentTotalsTest {
  @Test
  void 跨UTC日期按用户本地日期区分计划与还债() {
    var instant = Instant.parse("2026-09-08T17:00:00Z");
    var date = LocalDate.parse("2026-09-09");
    var result =
        RepaymentTotals.summarize(
            List.of(
                new TodoRepository.DurationBucket(instant, 60000, 120000, date.minusDays(1)),
                new TodoRepository.DurationBucket(instant, 900000, 800000, date),
                new TodoRepository.DurationBucket(instant, 123000, 456000)),
            List.of(
                new TodoRepository.CompletionBucket(instant, date.minusDays(2)),
                new TodoRepository.CompletionBucket(instant, date)),
            time -> LocalDate.ofInstant(time, ZoneId.of("Asia/Shanghai")));
    assertThat(result).isEqualTo(new RepaymentTotals(1, 60000, 120000));
  }

  @Test
  void 周期内逐个事件按发生日期分类而非窗口终点() {
    var date = LocalDate.parse("2026-09-08");
    var result =
        RepaymentTotals.summarize(
            List.of(
                new TodoRepository.DurationBucket(
                    Instant.parse("2026-09-08T10:00:00Z"), 100, 0, date),
                new TodoRepository.DurationBucket(
                    Instant.parse("2026-09-09T10:00:00Z"), 200, 300, date)),
            List.of(),
            time -> LocalDate.ofInstant(time, ZoneId.of("UTC")));
    assertThat(result).isEqualTo(new RepaymentTotals(0, 200, 300));
  }
}
