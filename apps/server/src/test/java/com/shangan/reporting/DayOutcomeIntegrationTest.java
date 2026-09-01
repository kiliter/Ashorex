package com.shangan.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.reporting.application.DayOutcomeService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** 验证无作战单日期的日终分类只认可可信学习、专注和模拟考试活动。 */
@SpringBootTest
@Import(DayOutcomeIntegrationTest.FixedClockConfiguration.class)
class DayOutcomeIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-09-01T16:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2026, 9, 1);

  @TempDir static Path databaseDirectory;

  @Autowired DayOutcomeService outcomes;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("day-outcome.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from daily_day_outcomes").update();
    jdbc.sql("delete from lesson_review_events").update();
    jdbc.sql("delete from watch_sessions").update();
    jdbc.sql("delete from focus_sessions").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql("delete from users").update();
    insertUserAndLesson();
  }

  @Test
  void noPlanAndNoEffectiveActivityBecomesSlacked() {
    assertThat(outcomes.settle("user-1", DATE, ZoneId.of("Asia/Shanghai"))).isEqualTo("SLACKED");
    assertThat(savedOutcome()).isEqualTo("SLACKED");
  }

  @Test
  void trustedFreeStudyWithoutPlanBecomesFreeStudy() {
    insertWatchSession("free-study", null, 10_000);

    assertThat(outcomes.settle("user-1", DATE, ZoneId.of("Asia/Shanghai"))).isEqualTo("FREE_STUDY");
  }

  @Test
  void reviewAuditDoesNotPreventSlackedOutcome() {
    // 复习只用于周报审计，因此即使有复习事件，也不能被日终视为有效学习。
    insertWatchSession("review-session", null, 0);
    jdbc.sql(
            "insert into lesson_review_events "
                + "(id,user_id,media_item_id,watch_session_id,reviewed_on,created_at) "
                + "values ('review-1','user-1','media-1','review-session',:date,:now)")
        .param("date", DATE.toString())
        .param("now", NOW.toEpochMilli())
        .update();

    assertThat(outcomes.settle("user-1", DATE, ZoneId.of("Asia/Shanghai"))).isEqualTo("SLACKED");
  }

  private void insertUserAndLesson() {
    jdbc.sql(
            "insert into users "
                + "(id,username,password_hash,display_name,role,timezone,alive_check_level,day_end_local_time,enabled,created_at,updated_at) "
                + "values ('user-1','learner','x','学习者','USER','Asia/Shanghai','OFF','23:00',1,1,1)")
        .update();
    jdbc.sql(
            "insert into courses (id,name,emby_parent_item_id,created_at,updated_at) "
                + "values ('course-1','行测','parent-1',1,1)")
        .update();
    jdbc.sql(
            "insert into media_items "
                + "(id,course_id,emby_item_id,title,duration_ms,created_at,updated_at) "
                + "values ('media-1','course-1','emby-1','资料分析',600000,1,1)")
        .update();
  }

  private void insertWatchSession(String id, String planItemId, long verifiedWatchMs) {
    long startedAt = Instant.parse("2026-09-01T02:00:00Z").toEpochMilli();
    jdbc.sql(
            """
            insert into watch_sessions (
              id,user_id,media_item_id,emby_item_id,plan_item_id,device_id,status,
              play_session_id,upstream_path,hls,duration_ms,started_position_ms,
              last_reported_position_ms,max_verified_position_ms,verified_watch_ms,
              last_sequence,last_heartbeat_at,alive_check_pending,started_at,created_at,updated_at
            ) values (
              :id,'user-1','media-1','emby-1',:planItemId,'device-1','STOPPED',
              'play-1','/Videos/emby-1/stream',0,600000,0,
              :verified,:verified,:verified,1,:started,0,:started,:started,:started
            )
            """)
        .param("id", id)
        .param("planItemId", planItemId)
        .param("verified", verifiedWatchMs)
        .param("started", startedAt)
        .update();
  }

  private String savedOutcome() {
    return jdbc.sql(
            "select outcome from daily_day_outcomes where user_id='user-1' and outcome_date=:date")
        .param("date", DATE.toString())
        .query(String.class)
        .single();
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
