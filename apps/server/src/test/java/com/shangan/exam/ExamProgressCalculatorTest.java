package com.shangan.exam;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.exam.application.ExamProgressCalculator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 固定时钟验证考试倒计时、所需速度、实际速度和预计完成日期。 */
class ExamProgressCalculatorTest {

  @Test
  void calculatesAtRiskProgressPressureInUserTimezone() {
    ZoneId timezone = ZoneId.of("Asia/Shanghai");
    Clock clock = Clock.fixed(Instant.parse("2026-08-30T01:00:00Z"), timezone);
    ExamProgressCalculator calculator = new ExamProgressCalculator(clock);

    ExamProgressCalculator.Progress progress =
        calculator.calculate(
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 18), timezone, 100, 19, 7);

    assertThat(progress.daysUntilExam()).isEqualTo(63);
    assertThat(progress.daysUntilTarget()).isEqualTo(49);
    assertThat(progress.remainingLessons()).isEqualTo(81);
    assertThat(progress.requiredDailyPace()).isCloseTo(81.0 / 49.0, within(0.0001));
    assertThat(progress.actualDailyPace()).isEqualTo(1.0);
    assertThat(progress.riskStatus()).isEqualTo("AT_RISK");
    assertThat(progress.projectedFinishDate()).isEqualTo(LocalDate.of(2026, 11, 19));
  }

  private static org.assertj.core.data.Offset<Double> within(double value) {
    return org.assertj.core.data.Offset.offset(value);
  }
}
