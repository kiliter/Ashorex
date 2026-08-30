package com.shangan.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.planning.application.DailyPlanCloseScheduler;
import com.shangan.planning.application.DailyPlanService;
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

/** 用固定时钟验证不同时区的日终边界和重复调度幂等性。 */
@SpringBootTest
@Import(DailyPlanCloseSchedulerIntegrationTest.FixedClockConfiguration.class)
class DailyPlanCloseSchedulerIntegrationTest {
  @TempDir static Path databaseDirectory;

  @Autowired DailyPlanService plans;
  @Autowired DailyPlanCloseScheduler scheduler;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("day-close.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from debt_repayments").update();
    jdbc.sql("delete from learning_debts").update();
    jdbc.sql("delete from plan_abandonments").update();
    jdbc.sql("delete from daily_plan_items").update();
    jdbc.sql("delete from daily_plans").update();
    jdbc.sql("delete from refresh_tokens").update();
    jdbc.sql("delete from users").update();
    insertUser("user-cn", "cn", "Asia/Shanghai");
    insertUser("user-us", "us", "America/New_York");

    LocalDate date = LocalDate.of(2026, 8, 30);
    createLockedFocusPlan("user-cn", date);
    createLockedFocusPlan("user-us", date);
  }

  @Test
  void closesOnlyUsersWhoseLocalDayEndHasPassed() {
    scheduler.closeDuePlans();
    scheduler.closeDuePlans();

    assertThat(status("user-cn")).isEqualTo("CLOSED_WITH_DEBT");
    assertThat(status("user-us")).isEqualTo("LOCKED");
    assertThat(
            jdbc.sql("select count(*) from learning_debts where user_id='user-cn'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  private void createLockedFocusPlan(String userId, LocalDate date) {
    plans.addItem(
        userId, date, new DailyPlanService.ItemDraft("FOCUS", "专注练习", null, null, 600, 0));
    plans.lock(userId, date);
  }

  private void insertUser(String id, String username, String timezone) {
    jdbc.sql(
            """
            insert into users (
              id, username, password_hash, display_name, role, timezone,
              alive_check_level, day_end_local_time, enabled, created_at, updated_at
            ) values (:id,:username,'x',:username,'USER',:timezone,'NORMAL','23:00',1,1,1)
            """)
        .param("id", id)
        .param("username", username)
        .param("timezone", timezone)
        .update();
  }

  private String status(String userId) {
    return jdbc.sql("select status from daily_plans where user_id=:userId")
        .param("userId", userId)
        .query(String.class)
        .single();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-08-30T15:30:00Z"), ZoneOffset.UTC);
    }
  }
}
