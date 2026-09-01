package com.shangan.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.planning.application.DailyPlanService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 场景 B 验收：开摆预览精确、关闭不可逆、欠债幂等并可由次日精确还清。 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AbandonAndDebtAcceptanceTest {
  @TempDir static Path databaseDirectory;

  @Autowired DailyPlanService plans;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("abandon-and-debt.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
  }

  @BeforeEach
  void setUp() {
    jdbc.sql(
            "insert into users (id,username,password_hash,display_name,role,timezone,alive_check_level,day_end_local_time,enabled,created_at,updated_at) "
                + "values ('user-1','learner','x','学习者','USER','Asia/Shanghai','OFF','23:59',1,1,1)")
        .update();
    jdbc.sql(
            "insert into courses (id,name,emby_parent_item_id,created_at,updated_at) "
                + "values ('course-1','行测','parent-1',1,1)")
        .update();
    jdbc.sql(
            "insert into media_items (id,course_id,emby_item_id,title,duration_ms,created_at,updated_at) "
                + "values ('media-1','course-1','emby-1','资料分析',600000,1,1)")
        .update();
  }

  @Test
  void previewsAbandonsAndRepaysExactRemainingWork() {
    LocalDate firstDay = LocalDate.of(2026, 8, 30);
    var withVideo =
        plans.addItem(
            "user-1",
            firstDay,
            new DailyPlanService.ItemDraft("VIDEO", "视频", "media-1", null, 1, 0));
    String videoItemId = withVideo.items().getFirst().id();
    plans.addItem(
        "user-1", firstDay, new DailyPlanService.ItemDraft("FOCUS", "申论练习", null, null, 300, 1));
    plans.lock("user-1", firstDay);
    plans.updateVideoWatchProgress("user-1", videoItemId, 120, false);

    var preview = plans.previewAbandon("user-1", firstDay);
    assertThat(preview.debtCount()).isEqualTo(2);
    assertThat(preview.addedDebtSeconds()).isEqualTo(780);

    assertThat(plans.abandon("user-1", firstDay, "OPEN_PALM", "今天状态不佳").status())
        .isEqualTo("ABANDONED");
    plans.abandon("user-1", firstDay, "OPEN_PALM", "重复请求");
    assertThat(count("learning_debts")).isEqualTo(2);
    assertThat(
            jdbc.sql("select remaining_seconds from learning_debts order by debt_type")
                .query(Long.class)
                .list())
        .containsExactly(300L, 480L);

    List<String> debtIds =
        jdbc.sql("select id from learning_debts order by debt_type").query(String.class).list();
    var repayment = plans.addDebtItems("user-1", firstDay.plusDays(1), debtIds);
    plans.lock("user-1", firstDay.plusDays(1));
    repayment
        .items()
        .forEach(item -> plans.updateProgress("user-1", item.id(), item.plannedSeconds()));

    assertThat(
            jdbc.sql("select status from learning_debts order by debt_type")
                .query(String.class)
                .list())
        .containsOnly("PAID");
    assertThat(count("debt_repayments")).isEqualTo(2);
  }

  private long count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Long.class).single();
  }
}
