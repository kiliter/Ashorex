package com.shangan.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.learning.api.WatchHeartbeatRequest;
import com.shangan.learning.application.WatchSessionService;
import com.shangan.learning.infrastructure.WatchSessionBootstrapRepository;
import com.shangan.planning.application.BattleOrderService;
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

/** 使用真实 SQLite 事务验证心跳、计划关闭和欠债之间的完整可信进度链路。 */
@SpringBootTest
@Import(WatchSessionIntegrationTest.MutableClockConfiguration.class)
class WatchSessionIntegrationTest {
  private static final Instant START = Instant.parse("2026-08-30T00:00:00Z");

  @TempDir static Path databaseDirectory;

  @Autowired WatchSessionService sessions;
  @Autowired DailyPlanService plans;
  @Autowired BattleOrderService battleOrders;
  @Autowired JdbcClient jdbc;
  @Autowired MutableClock clock;
  @Autowired WatchSessionBootstrapRepository bootstrapSessions;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("watch.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    clock.set(START);
    jdbc.sql("delete from alive_checks").update();
    jdbc.sql("delete from lesson_review_events").update();
    jdbc.sql("delete from watch_sessions").update();
    jdbc.sql("delete from video_progress").update();
    jdbc.sql("delete from debt_repayments").update();
    jdbc.sql("delete from learning_debts").update();
    jdbc.sql("delete from plan_abandonments").update();
    jdbc.sql("delete from daily_plan_items").update();
    jdbc.sql("delete from daily_plans").update();
    jdbc.sql("delete from exam_goal_courses").update();
    jdbc.sql("delete from exam_goals").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql("delete from refresh_tokens").update();
    jdbc.sql("delete from users").update();
    insertFixtures();
  }

  @Test
  void acceptedHeartbeatUpdatesVideoAndLinkedPlanWithAbsoluteProgress() {
    var plan = createLockedPlan();
    insertSession("session-1", plan.items().getFirst().id(), 0, 0, 0);
    clock.advanceSeconds(10);

    var response =
        sessions.heartbeat(
            "user-1", "session-1", new WatchHeartbeatRequest(1, 10_000, true, true, 1.0d));

    assertThat(response.trustedPositionMs()).isEqualTo(10_000);
    assertThat(response.seekAllowed()).isTrue();
    assertThat(
            jdbc.sql("select max_verified_position_ms from video_progress where user_id='user-1'")
                .query(Long.class)
                .single())
        .isEqualTo(10_000);
    assertThat(completedSeconds(plan.items().getFirst().id())).isEqualTo(10);
  }

  @Test
  void unsupportedPlaybackSpeedDoesNotAdvanceTrustedProgress() {
    var plan = createLockedPlan();
    insertSession("session-speed", plan.items().getFirst().id(), 0, 0, 0);
    clock.advanceSeconds(10);

    assertThatThrownBy(
            () ->
                sessions.heartbeat(
                    "user-1",
                    "session-speed",
                    new WatchHeartbeatRequest(1, 11_000, true, true, 1.1d)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo("WATCH_PLAYBACK_SPEED_INVALID"));

    assertThat(
            jdbc.sql("select max_verified_position_ms from watch_sessions where id='session-speed'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void planClosePersistsFinalTrustedPositionBeforeCreatingOnlyRemainderDebt() {
    var plan = createLockedPlan();
    String itemId = plan.items().getFirst().id();
    insertSession("session-2", itemId, 120_000, 120_000, 120_000);

    plans.closeForDayEnd("user-1", plan.id());

    assertThat(completedSeconds(itemId)).isEqualTo(120);
    assertThat(
            jdbc.sql(
                    "select remaining_seconds from learning_debts "
                        + "where source_plan_item_id=:itemId and debt_type='VIDEO_WATCH'")
                .param("itemId", itemId)
                .query(Long.class)
                .single())
        .isEqualTo(480);
    assertThat(sessionStatus("session-2")).isEqualTo("STOPPED");

    insertSession("session-direct", null, 120_000, 120_000, 0);
    clock.advanceSeconds(10);
    sessions.heartbeat(
        "user-1", "session-direct", new WatchHeartbeatRequest(1, 130_000, true, true, 1.0d));
    assertThat(
            jdbc.sql(
                    "select remaining_seconds from learning_debts "
                        + "where source_plan_item_id=:itemId and debt_type='VIDEO_WATCH'")
                .param("itemId", itemId)
                .query(Long.class)
                .single())
        .isEqualTo(470);
  }

  @Test
  void bootstrapRepositoryPersistsResumePositionAndAliveThreshold() {
    bootstrapSessions.insert(
        new WatchSessionBootstrapRepository.SessionPlayback(
            "session-bootstrap",
            "user-1",
            "media-1",
            "emby-1",
            null,
            "device-1",
            "play-1",
            "/Videos/emby-1/stream",
            false,
            600_000,
            45_000,
            2_400_000L),
        START);

    assertThat(
            jdbc.sql("select started_position_ms from watch_sessions where id='session-bootstrap'")
                .query(Long.class)
                .single())
        .isEqualTo(45_000);
    assertThat(
            jdbc.sql(
                    "select alive_check_due_position_ms from watch_sessions where id='session-bootstrap'")
                .query(Long.class)
                .single())
        .isEqualTo(2_400_000);
  }

  @Test
  void dueAliveCheckPausesCountingAndRecordsTimeoutFailure() {
    var plan = createLockedPlan();
    insertSession("session-3", plan.items().getFirst().id(), 0, 0, 0);
    jdbc.sql("update watch_sessions set alive_check_due_position_ms=5000 where id='session-3'")
        .update();
    clock.advanceSeconds(10);

    var required =
        sessions.heartbeat(
            "user-1", "session-3", new WatchHeartbeatRequest(1, 10_000, true, true, 1.0d));
    assertThat(required.aliveCheckRequired()).isTrue();
    assertThat(required.status()).isEqualTo("PAUSED");

    clock.advanceSeconds(61);
    var pending =
        sessions.heartbeat(
            "user-1", "session-3", new WatchHeartbeatRequest(2, 10_000, true, true, 1.0d));
    assertThat(pending.verifiedWatchMs()).isEqualTo(10_000);
    assertThat(
            jdbc.sql("select status from alive_checks where watch_session_id='session-3'")
                .query(String.class)
                .single())
        .isEqualTo("FAILED");

    var resumed = sessions.confirmAliveCheck("user-1", "session-3");
    assertThat(resumed.aliveCheckRequired()).isFalse();
    assertThat(resumed.status()).isEqualTo("ACTIVE");
  }

  @Test
  void reviewShortcutRecordsOneAuditEventWithoutChangingTrustedLearningProgress() {
    // 已完成课时再次加入作战单时，服务端必须自动转换为复习快捷入口。
    jdbc.sql(
            "insert into video_progress "
                + "(id,user_id,media_item_id,max_verified_position_ms,verified_watch_ms,completed_at,last_watched_at,created_at,updated_at) "
                + "values ('completed-progress','user-1','media-1',600000,600000,:now,:now,:now,:now)")
        .param("now", START.toEpochMilli())
        .update();
    var order =
        battleOrders.save(
            "user-1",
            LocalDate.of(2026, 8, 30),
            new BattleOrderService.SaveCommand(
                0, List.of(new BattleOrderService.ItemCommand(null, "VIDEO", "media-1", null, 0))));
    String reviewItemId = order.items().getFirst().id();
    assertThat(order.items().getFirst().itemType()).isEqualTo("REVIEW_SHORTCUT");

    insertSession("review-session", reviewItemId, 0, 0, 0);
    clock.advanceSeconds(10);
    sessions.heartbeat(
        "user-1", "review-session", new WatchHeartbeatRequest(1, 10_000, true, true, 1.0d));
    clock.advanceSeconds(10);
    sessions.heartbeat(
        "user-1", "review-session", new WatchHeartbeatRequest(2, 20_000, true, true, 1.0d));

    assertThat(
            jdbc.sql(
                    "select count(*) from lesson_review_events "
                        + "where user_id='user-1' and watch_session_id='review-session'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "select verified_watch_ms from video_progress "
                        + "where user_id='user-1' and media_item_id='media-1'")
                .query(Long.class)
                .single())
        .isEqualTo(600_000);
    assertThat(completedSeconds(reviewItemId)).isZero();
  }

  private DailyPlanService.PlanView createLockedPlan() {
    LocalDate date = LocalDate.of(2026, 8, 30);
    plans.addItem(
        "user-1", date, new DailyPlanService.ItemDraft("VIDEO", "忽略", "media-1", null, 1, 0));
    return plans.lock("user-1", date);
  }

  private void insertSession(
      String id, String planItemId, long lastPosition, long maximum, long verifiedWatch) {
    jdbc.sql(
            """
            insert into watch_sessions (
              id,user_id,media_item_id,emby_item_id,plan_item_id,device_id,status,
              play_session_id,upstream_path,hls,duration_ms,started_position_ms,
              last_reported_position_ms,max_verified_position_ms,verified_watch_ms,
              last_sequence,last_heartbeat_at,alive_check_pending,started_at,created_at,updated_at
            ) values (
              :id,'user-1','media-1','emby-1',:itemId,'device-1','ACTIVE',
              'play-1','/Videos/emby-1/stream',0,600000,0,
              :lastPosition,:maximum,:verifiedWatch,0,:now,0,:now,:now,:now
            )
            """)
        .param("id", id)
        .param("itemId", planItemId)
        .param("lastPosition", lastPosition)
        .param("maximum", maximum)
        .param("verifiedWatch", verifiedWatch)
        .param("now", START.toEpochMilli())
        .update();
  }

  private void insertFixtures() {
    jdbc.sql(
            """
            insert into users (
              id,username,password_hash,display_name,role,timezone,
              alive_check_level,day_end_local_time,enabled,created_at,updated_at
            ) values ('user-1','learner','x','学习者','USER','Asia/Shanghai','OFF','23:00',1,1,1)
            """)
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

  private long completedSeconds(String itemId) {
    return jdbc.sql("select completed_seconds from daily_plan_items where id=:id")
        .param("id", itemId)
        .query(Long.class)
        .single();
  }

  private String sessionStatus(String id) {
    return jdbc.sql("select status from watch_sessions where id=:id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  /** 可由测试显式推进、生产代码只读取的 UTC 时钟。 */
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
