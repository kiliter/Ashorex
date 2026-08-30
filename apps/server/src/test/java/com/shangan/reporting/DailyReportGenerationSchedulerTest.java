package com.shangan.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.reporting.application.DailyReportGenerationScheduler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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

/** 验证终态计划与前一用户本地日会被幂等物化为日报快照。 */
@SpringBootTest
@Import(DailyReportGenerationSchedulerTest.FixedClockConfiguration.class)
class DailyReportGenerationSchedulerTest {
  @TempDir static Path databaseDirectory;

  @Autowired DailyReportGenerationScheduler scheduler;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("report-scheduler.db"));
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
  void materializesTerminalAndPreviousDayIdempotently() {
    scheduler.materializeReports();
    String firstPayload =
        jdbc.sql("select payload_json from daily_reports where report_date='2026-08-30'")
            .query(String.class)
            .single();

    scheduler.materializeReports();

    assertThat(
            jdbc.sql("select report_date from daily_reports order by report_date")
                .query(String.class)
                .list())
        .containsExactly("2026-08-29", "2026-08-30");
    assertThat(
            jdbc.sql("select payload_json from daily_reports where report_date='2026-08-30'")
                .query(String.class)
                .single())
        .isEqualTo(firstPayload);
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
