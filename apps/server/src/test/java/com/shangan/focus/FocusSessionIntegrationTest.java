package com.shangan.focus;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.focus.application.FocusSessionService;
import com.shangan.planning.application.DailyPlanService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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

/** 验证暂停和计划关闭都会先同步服务端有效秒数，再按剩余量生成 FOCUS 欠债。 */
@SpringBootTest
@Import(FocusSessionIntegrationTest.MutableClockConfiguration.class)
class FocusSessionIntegrationTest {
  private static final Instant START = Instant.parse("2026-08-30T00:00:00Z");

  @TempDir static Path databaseDirectory;

  @Autowired FocusSessionService focus;
  @Autowired DailyPlanService plans;
  @Autowired MutableClock clock;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("focus.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    clock.set(START);
    for (String table :
        List.of(
            "daily_reports",
            "focus_sessions",
            "debt_repayments",
            "learning_debts",
            "plan_abandonments",
            "daily_plan_items",
            "daily_plans",
            "refresh_tokens",
            "users")) {
      jdbc.sql("delete from " + table).update();
    }
    jdbc.sql(
            "insert into users (id,username,password_hash,display_name,role,timezone,alive_check_level,day_end_local_time,enabled,created_at,updated_at) values ('user-1','learner','x','学习者','USER','UTC','OFF','23:59',1,1,1)")
        .update();
  }

  @Test
  void planCloseCancelsActiveSessionAndCreatesOnlyRemainingDebt() {
    LocalDate date = LocalDate.of(2026, 8, 30);
    var draft =
        plans.addItem(
            "user-1", date, new DailyPlanService.ItemDraft("FOCUS", "申论练习", null, null, 100, 0));
    String itemId = draft.items().getFirst().id();
    var locked = plans.lock("user-1", date);
    String sessionId =
        focus
            .start("user-1", new FocusSessionService.StartCommand(itemId, null, "PRACTICE", 100))
            .id();

    clock.advanceSeconds(40);
    focus.pause("user-1", sessionId);
    assertThat(completedSeconds(itemId)).isEqualTo(40);
    clock.advanceSeconds(10);
    focus.resume("user-1", sessionId);
    clock.advanceSeconds(20);

    plans.closeForDayEnd("user-1", locked.id());

    assertThat(completedSeconds(itemId)).isEqualTo(60);
    assertThat(
            jdbc.sql("select status from focus_sessions where id=:id")
                .param("id", sessionId)
                .query(String.class)
                .single())
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.sql("select remaining_seconds from learning_debts where debt_type='FOCUS'")
                .query(Long.class)
                .single())
        .isEqualTo(40);
  }

  private long completedSeconds(String itemId) {
    return jdbc.sql("select completed_seconds from daily_plan_items where id=:id")
        .param("id", itemId)
        .query(Long.class)
        .single();
  }

  static final class MutableClock extends Clock {
    private Instant instant = START;

    void set(Instant value) {
      instant = value;
    }

    void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MutableClockConfiguration {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }
}
