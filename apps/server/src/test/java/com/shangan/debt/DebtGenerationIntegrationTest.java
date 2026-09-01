package com.shangan.debt;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.debt.application.DebtService;
import com.shangan.planning.application.DailyPlanService;
import com.shangan.planning.application.VideoTaskRequirementPort;
import com.shangan.planning.domain.PlanItem;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证开摆/日终欠债幂等、VIDEO 组合完成条件和绝对值还债增量。 */
@SpringBootTest
@Import(DebtGenerationIntegrationTest.QuizRequirementConfiguration.class)
class DebtGenerationIntegrationTest {
  @TempDir static Path databaseDirectory;

  @Autowired DailyPlanService plans;
  @Autowired DebtService debts;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("debt.db"));
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
    jdbc.sql("delete from exam_goal_courses").update();
    jdbc.sql("delete from exam_goals").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql("delete from refresh_tokens").update();
    jdbc.sql("delete from users").update();
    jdbc.sql(
            """
            insert into users (
              id, username, password_hash, display_name, role, timezone,
              alive_check_level, day_end_local_time, enabled, created_at, updated_at
            ) values ('user-1','alice','x','Alice','USER','Asia/Shanghai','NORMAL','23:59',1,1,1)
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
            ) values ('lesson-1','course-1','emby-1','资料分析',1000000,1,0,1,1,1)
            """)
        .update();
  }

  @Test
  void closeIsIdempotentAndRepaymentStoresOnlyPositiveDeltas() {
    LocalDate sourceDate = LocalDate.of(2026, 8, 30);
    var videoPlan =
        plans.addItem(
            "user-1",
            sourceDate,
            new DailyPlanService.ItemDraft("VIDEO", "", "lesson-1", null, 1, 0));
    var withFocus =
        plans.addItem(
            "user-1",
            sourceDate,
            new DailyPlanService.ItemDraft("FOCUS", "申论练习", null, null, 500, 1));
    String videoItemId = videoPlan.items().getFirst().id();
    plans.lock("user-1", sourceDate);
    plans.updateProgress("user-1", videoItemId, 300);

    plans.closeForDayEnd("user-1", withFocus.id());
    plans.closeForDayEnd("user-1", withFocus.id());

    assertThat(
            jdbc.sql("select debt_type from learning_debts order by debt_type")
                .query(String.class)
                .list())
        .containsExactly("FOCUS", "QUIZ", "VIDEO_WATCH");
    assertThat(
            jdbc.sql("select remaining_seconds from learning_debts where debt_type='VIDEO_WATCH'")
                .query(Long.class)
                .single())
        .isEqualTo(700);
    assertThat(
            jdbc.sql("select remaining_seconds from learning_debts where debt_type='QUIZ'")
                .query(Long.class)
                .single())
        .isEqualTo(600);

    String debtId =
        jdbc.sql("select id from learning_debts where debt_type='VIDEO_WATCH'")
            .query(String.class)
            .single();
    LocalDate repaymentDate = sourceDate.plusDays(1);
    var repayment = plans.addDebtItems("user-1", repaymentDate, java.util.List.of(debtId));
    String repaymentItemId = repayment.items().getFirst().id();
    plans.updateProgress("user-1", repaymentItemId, 300);
    plans.updateProgress("user-1", repaymentItemId, 300);
    plans.updateProgress("user-1", repaymentItemId, 420);

    assertThat(
            jdbc.sql("select repaid_seconds from debt_repayments order by created_at, rowid")
                .query(Long.class)
                .list())
        .containsExactly(300L, 120L);
  }

  @Test
  void videoCompletesOnlyAfterWatchAndRequiredQuizInEitherOrder() {
    LocalDate first = LocalDate.of(2026, 9, 1);
    var plan =
        plans.addItem(
            "user-1", first, new DailyPlanService.ItemDraft("VIDEO", "", "lesson-1", null, 1, 0));
    String itemId = plan.items().getFirst().id();
    plans.lock("user-1", first);
    plans.updateProgress("user-1", itemId, 1000);
    assertThat(itemStatus(itemId)).isEqualTo("PENDING");
    plans.markQuizCompleted("user-1", itemId);
    assertThat(itemStatus(itemId)).isEqualTo("COMPLETED");

    LocalDate second = first.plusDays(1);
    var inverse =
        plans.addItem(
            "user-1", second, new DailyPlanService.ItemDraft("VIDEO", "", "lesson-1", null, 1, 0));
    String inverseItemId = inverse.items().getFirst().id();
    plans.lock("user-1", second);
    plans.markQuizCompleted("user-1", inverseItemId);
    assertThat(itemStatus(inverseItemId)).isEqualTo("PENDING");
    plans.updateProgress("user-1", inverseItemId, 1000);
    assertThat(itemStatus(inverseItemId)).isEqualTo("COMPLETED");
  }

  @Test
  void unfinishedMockExamDoesNotCreateLearningDebt() {
    // 模拟考试是复习任务，只保留执行与审计记录，日终不能把它转成学习欠债。
    jdbc.sql(
            """
            insert into daily_plans (
              id,user_id,plan_date,status,lifecycle_status,version,created_at,updated_at
            ) values ('plan-1','user-1','2026-09-01','LOCKED','ACTIVE',1,1,1)
            """)
        .update();
    jdbc.sql(
            """
            insert into daily_plan_items (
              id,plan_id,item_type,item_kind,title,planned_seconds,completed_seconds,
              watch_completed,quiz_required,quiz_completed,status,sort_order,created_at,updated_at
            ) values (
              'mock-exam-item','plan-1','FOCUS','MOCK_EXAM','行测',7200,0,
              0,0,0,'PENDING',0,1,1
            )
            """)
        .update();
    PlanItem mockExam =
        new PlanItem(
            "mock-exam-item",
            "plan-1",
            "MOCK_EXAM",
            "行测",
            null,
            null,
            7200,
            0,
            false,
            false,
            false,
            "PENDING",
            0,
            null);

    debts.generate(
        "user-1",
        LocalDate.of(2026, 9, 1),
        "DAY_END",
        List.of(mockExam),
        Instant.parse("2026-09-01T16:00:00Z"));

    assertThat(jdbc.sql("select count(*) from learning_debts").query(Long.class).single()).isZero();
  }

  private String itemStatus(String itemId) {
    return jdbc.sql("select status from daily_plan_items where id=:id")
        .param("id", itemId)
        .query(String.class)
        .single();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class QuizRequirementConfiguration {
    @Bean
    VideoTaskRequirementPort quizRequirement() {
      return mediaItemId -> true;
    }
  }
}
