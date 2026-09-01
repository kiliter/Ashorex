package com.shangan.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.reporting.application.DailyReportService;
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

/** 使用原始业务行验证日报全部指标与晚间审判精确一致。 */
@SpringBootTest
@Import(DailyReportAggregationTest.FixedClockConfiguration.class)
class DailyReportAggregationTest {
  private static final Instant NOW = Instant.parse("2026-08-30T15:00:00Z");

  @TempDir static Path databaseDirectory;

  @Autowired DailyReportService reports;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("daily-report.db"));
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
  void aggregatesPlanWatchFocusQuizDebtAliveAndAbandonmentExactly() {
    DailyReportService.DailyReportView report =
        reports.generate("user-1", LocalDate.of(2026, 8, 30));

    assertThat(report.planStatus()).isEqualTo("ABANDONED");
    assertThat(report.dayOutcome()).isEqualTo("CLOSED_WITH_DEBT");
    assertThat(report.plannedSeconds()).isEqualTo(1500);
    assertThat(report.videoStudySeconds()).isEqualTo(600);
    assertThat(report.focusSeconds()).isEqualTo(300);
    assertThat(report.completedTasks()).isEqualTo(1);
    assertThat(report.totalTasks()).isEqualTo(2);
    assertThat(report.completionRate()).isEqualTo(50);
    assertThat(report.videoCompletedCount()).isEqualTo(1);
    assertThat(report.mockExamCompletedCount()).isZero();
    assertThat(report.mockExamAwaitingUploadCount()).isZero();
    assertThat(report.answerCount()).isEqualTo(2);
    assertThat(report.answerAccuracy()).isEqualTo(50);
    assertThat(report.aliveCheckFailureCount()).isEqualTo(1);
    assertThat(report.abandoned()).isTrue();
    assertThat(report.newDebtSeconds()).isEqualTo(700);
    assertThat(report.repaidDebtSeconds()).isEqualTo(100);
    assertThat(report.openDebtSeconds()).isEqualTo(600);
    assertThat(report.judgmentText()).isEqualTo("你在 14:00 选择开摆，原因：今天状态很差。今日新增欠债 700 秒，计划已不可撤销地关闭。");
    assertThat(jdbc.sql("select count(*) from daily_reports").query(Integer.class).single())
        .isEqualTo(1);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
