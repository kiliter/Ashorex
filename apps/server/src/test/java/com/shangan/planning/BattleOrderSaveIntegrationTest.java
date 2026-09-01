package com.shangan.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.focus.application.MockExamPresetService;
import com.shangan.planning.application.BattleOrderService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

/** 验证今日作战单以完整快照原子保存，并正确区分学习任务与复习快捷入口。 */
@SpringBootTest
@Import(BattleOrderSaveIntegrationTest.FixedClockConfiguration.class)
class BattleOrderSaveIntegrationTest {
  @TempDir static Path databaseDirectory;

  @Autowired BattleOrderService battleOrders;
  @Autowired MockExamPresetService presets;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("battle-order.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from lesson_review_events").update();
    jdbc.sql("delete from mock_exam_attachments").update();
    jdbc.sql("delete from mock_exam_sessions").update();
    jdbc.sql("delete from mock_exam_presets").update();
    jdbc.sql("delete from daily_plan_revisions").update();
    jdbc.sql("delete from debt_repayments").update();
    jdbc.sql("delete from learning_debts").update();
    jdbc.sql("delete from plan_abandonments").update();
    jdbc.sql("delete from daily_plan_items").update();
    jdbc.sql("delete from daily_plans").update();
    jdbc.sql("delete from video_progress").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql("delete from refresh_tokens").update();
    jdbc.sql("delete from users").update();
    insertFixtures();
  }

  @Test
  void savesWholeSnapshotAndTurnsCompletedLessonIntoReviewShortcut() {
    var preset =
        presets.create("user-1", new MockExamPresetService.PresetCommand("行测模拟考试", 7_200, 0));

    var saved =
        battleOrders.save(
            "user-1",
            LocalDate.of(2026, 9, 1),
            new BattleOrderService.SaveCommand(
                0,
                List.of(
                    new BattleOrderService.ItemCommand(null, "VIDEO", "lesson-done", null, 0),
                    new BattleOrderService.ItemCommand(null, "MOCK_EXAM", null, preset.id(), 1),
                    new BattleOrderService.ItemCommand(null, "VIDEO", "lesson-new", null, 2))));

    assertThat(saved.status()).isEqualTo("ACTIVE");
    assertThat(saved.version()).isEqualTo(1);
    assertThat(saved.items())
        .extracting(BattleOrderService.ItemView::itemType)
        .containsExactly("VIDEO", "REVIEW_SHORTCUT", "MOCK_EXAM");
    assertThat(saved.items())
        .extracting(BattleOrderService.ItemView::mediaItemId)
        .containsExactly("lesson-new", "lesson-done", null);
    assertThat(jdbc.sql("select count(*) from daily_plan_revisions").query(Integer.class).single())
        .isEqualTo(1);
  }

  @Test
  void removesUnstartedItemAndRejectsStaleVersionWithoutPartialWrite() {
    var first =
        battleOrders.save(
            "user-1",
            LocalDate.of(2026, 9, 1),
            new BattleOrderService.SaveCommand(
                0,
                List.of(
                    new BattleOrderService.ItemCommand(null, "VIDEO", "lesson-new", null, 0),
                    new BattleOrderService.ItemCommand(null, "VIDEO", "lesson-done", null, 1))));

    var revised =
        battleOrders.save(
            "user-1",
            first.date(),
            new BattleOrderService.SaveCommand(
                first.version(),
                List.of(
                    new BattleOrderService.ItemCommand(
                        first.items().get(1).id(), "REVIEW_SHORTCUT", "lesson-done", null, 0))));

    assertThat(revised.version()).isEqualTo(2);
    assertThat(revised.items())
        .extracting(BattleOrderService.ItemView::mediaItemId)
        .containsExactly("lesson-done");

    assertThatThrownBy(
            () ->
                battleOrders.save(
                    "user-1",
                    first.date(),
                    new BattleOrderService.SaveCommand(first.version(), List.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("其他页面修改");
    assertThat(battleOrders.get("user-1", first.date()).items()).hasSize(1);
  }

  @Test
  void repeatedIdenticalSaveKeepsVersionAndDoesNotCreateRevision() {
    var first =
        battleOrders.save(
            "user-1",
            LocalDate.of(2026, 9, 1),
            new BattleOrderService.SaveCommand(
                0,
                List.of(
                    new BattleOrderService.ItemCommand(null, "VIDEO", "lesson-new", null, 99))));

    var repeated =
        battleOrders.save(
            "user-1",
            first.date(),
            new BattleOrderService.SaveCommand(
                first.version(),
                List.of(
                    new BattleOrderService.ItemCommand(
                        first.items().getFirst().id(), "VIDEO", "lesson-new", null, 0))));

    assertThat(repeated.version()).isEqualTo(first.version());
    assertThat(jdbc.sql("select count(*) from daily_plan_revisions").query(Integer.class).single())
        .isEqualTo(1);
  }

  private void insertFixtures() {
    jdbc.sql(
            """
            insert into users (
              id,username,password_hash,display_name,role,timezone,
              alive_check_level,day_end_local_time,enabled,created_at,updated_at
            ) values ('user-1','learner','x','学习者','USER','Asia/Shanghai','NORMAL','23:59',1,1,1)
            """)
        .update();
    jdbc.sql(
            """
            insert into courses (
              id,name,description,emby_parent_item_id,enabled,sort_order,created_at,updated_at
            ) values ('course-1','行测','','parent-1',1,0,1,1)
            """)
        .update();
    jdbc.sql(
            """
            insert into media_items (
              id,course_id,emby_item_id,title,duration_ms,enabled,sort_order,available,created_at,updated_at
            ) values
              ('lesson-new','course-1','emby-new','新课时',3600000,1,0,1,1,1),
              ('lesson-done','course-1','emby-done','已学课时',1800000,1,1,1,1,1)
            """)
        .update();
    jdbc.sql(
            """
            insert into video_progress (
              id,user_id,media_item_id,max_verified_position_ms,verified_watch_ms,
              completed_at,last_watched_at,created_at,updated_at
            ) values ('progress-1','user-1','lesson-done',1770000,1770000,1,1,1,1)
            """)
        .update();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), ZoneOffset.UTC);
    }
  }
}
