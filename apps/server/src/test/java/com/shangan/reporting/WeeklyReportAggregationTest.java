package com.shangan.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.reporting.application.WeeklyReportService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证七日趋势、总量和与上周对比均来自同一组日报原始指标。 */
@SpringBootTest
@Import(WeeklyReportAggregationTest.FixedClockConfiguration.class)
class WeeklyReportAggregationTest {
  @TempDir static Path databaseDirectory;

  @Autowired WeeklyReportService reports;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("weekly-report.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    ReportingFixtures.clear(jdbc);
    ReportingFixtures.insertCompleteDay(jdbc);
  }

  @Test
  void aggregatesSevenDaysAndComparesPreviousWeek() {
    WeeklyReportService.WeeklyReportView report =
        reports.generate("user-1", LocalDate.of(2026, 8, 24));

    assertThat(report.days()).hasSize(7);
    assertThat(report.totalEffectiveStudySeconds()).isEqualTo(900);
    assertThat(report.videoStudySeconds()).isEqualTo(600);
    assertThat(report.focusSeconds()).isEqualTo(300);
    assertThat(report.answerCount()).isEqualTo(2);
    assertThat(report.answerAccuracy()).isEqualTo(50);
    assertThat(report.planCompletionRate()).isEqualTo(50);
    assertThat(report.newDebtSeconds()).isEqualTo(700);
    assertThat(report.repaidDebtSeconds()).isEqualTo(100);
    assertThat(report.abandonmentCount()).isEqualTo(1);
    assertThat(report.slackedDayCount()).isZero();
    assertThat(report.reviewedLessons())
        .singleElement()
        .satisfies(
            review -> {
              assertThat(review.mediaItemId()).isEqualTo("media-1");
              assertThat(review.lessonTitle()).isEqualTo("资料分析");
              assertThat(review.reviewCount()).isEqualTo(1);
            });
    assertThat(report.aliveCheckFailureCount()).isEqualTo(1);
    assertThat(report.previousWeekEffectiveStudySeconds()).isZero();
    assertThat(report.effectiveStudySecondsChange()).isEqualTo(900);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-08-30T15:00:00Z"), ZoneOffset.UTC);
    }
  }
}
