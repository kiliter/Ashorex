package com.shangan.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.planning.application.DailyPlanService;
import com.shangan.quiz.application.QuizService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证完整答卷的事务保存、计划完成和同视频 QUIZ 欠债幂等结清。 */
@SpringBootTest
class QuizAttemptIntegrationTest {
  @TempDir static Path databaseDirectory;

  @Autowired QuizService quizzes;
  @Autowired DailyPlanService plans;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("quiz-attempt.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    clearFixtures();
    insertFixtures();
  }

  @Test
  void completeAttemptScoresPersistsCompletesPlanAndSettlesDebtOnce() {
    LocalDate date = LocalDate.of(2026, 8, 30);
    var draft =
        plans.addItem(
            "user-1", date, new DailyPlanService.ItemDraft("VIDEO", "", "media-1", null, 1, 0));
    String itemId = draft.items().getFirst().id();
    plans.lock("user-1", date);
    plans.updateVideoWatchProgress("user-1", itemId, 600, true);
    insertOpenQuizDebt(itemId);

    QuizService.AttemptResult result =
        quizzes.submit(
            "user-1",
            "media-1",
            new QuizService.AttemptCommand(
                itemId,
                25_000,
                List.of(
                    new QuizService.AnswerCommand("question-1", "question-1-b", 12_000),
                    new QuizService.AnswerCommand("question-2", "question-2-false", 13_000))));

    assertThat(result.score()).isEqualTo(50);
    assertThat(result.correctCount()).isEqualTo(1);
    assertThat(result.answers())
        .extracting(QuizService.AnswerResult::correct)
        .containsExactly(true, false);
    assertThat(result.answers())
        .extracting(QuizService.AnswerResult::explanation)
        .containsExactly("单选解析", "判断解析");
    assertThat(value("select status from daily_plan_items where id='" + itemId + "'"))
        .isEqualTo("COMPLETED");
    assertThat(value("select status from learning_debts where id='debt-quiz'")).isEqualTo("PAID");
    assertThat(count("quiz_attempts")).isEqualTo(1);
    assertThat(count("quiz_answers")).isEqualTo(2);
    assertThat(count("debt_repayments")).isEqualTo(1);

    quizzes.submit(
        "user-1",
        "media-1",
        new QuizService.AttemptCommand(
            null,
            20_000,
            List.of(
                new QuizService.AnswerCommand("question-1", "question-1-b", 10_000),
                new QuizService.AnswerCommand("question-2", "question-2-true", 10_000))));

    assertThat(count("quiz_attempts")).isEqualTo(2);
    assertThat(count("debt_repayments")).isEqualTo(1);
  }

  @Test
  void rejectsMissingAnswersWithoutSavingPartialAttempt() {
    assertThatThrownBy(
            () ->
                quizzes.submit(
                    "user-1",
                    "media-1",
                    new QuizService.AttemptCommand(
                        null,
                        5_000,
                        List.of(
                            new QuizService.AnswerCommand("question-1", "question-1-b", 5_000)))))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error ->
                assertThat(((BusinessException) error).errorCode())
                    .isEqualTo("QUIZ_ANSWERS_INCOMPLETE"));
    assertThat(count("quiz_attempts")).isZero();
  }

  private void clearFixtures() {
    for (String table :
        List.of(
            "quiz_answers",
            "quiz_attempts",
            "question_options",
            "questions",
            "alive_checks",
            "watch_sessions",
            "video_progress",
            "debt_repayments",
            "learning_debts",
            "daily_plan_items",
            "daily_plans",
            "media_items",
            "courses",
            "refresh_tokens",
            "users")) {
      jdbc.sql("delete from " + table).update();
    }
  }

  private void insertFixtures() {
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
    jdbc.sql(
            "insert into video_progress (id,user_id,media_item_id,max_verified_position_ms,verified_watch_ms,completed_at,last_watched_at,created_at,updated_at) "
                + "values ('progress-1','user-1','media-1',600000,600000,1,1,1,1)")
        .update();
    insertQuestion("question-1", "SINGLE_CHOICE", "单选解析", "A", "B");
    insertQuestion("question-2", "TRUE_FALSE", "判断解析", "正确", "错误");
  }

  private void insertQuestion(
      String id, String type, String explanation, String first, String second) {
    jdbc.sql(
            "insert into questions (id,media_item_id,question_type,content,explanation,enabled,sort_order,created_at,updated_at) "
                + "values (:id,'media-1',:type,:content,:explanation,1,:sortOrder,1,1)")
        .param("id", id)
        .param("type", type)
        .param("content", "题目 " + id)
        .param("explanation", explanation)
        .param("sortOrder", id.equals("question-1") ? 0 : 1)
        .update();
    jdbc.sql(
            "insert into question_options (id,question_id,content,correct,sort_order) values "
                + "(:firstId,:id,:first,:firstCorrect,0),(:secondId,:id,:second,:secondCorrect,1)")
        .param("firstId", id + (type.equals("TRUE_FALSE") ? "-true" : "-a"))
        .param("secondId", id + (type.equals("TRUE_FALSE") ? "-false" : "-b"))
        .param("id", id)
        .param("first", first)
        .param("second", second)
        .param("firstCorrect", type.equals("TRUE_FALSE") ? 1 : 0)
        .param("secondCorrect", type.equals("TRUE_FALSE") ? 0 : 1)
        .update();
  }

  private void insertOpenQuizDebt(String sourceItemId) {
    jdbc.sql(
            "insert into learning_debts (id,user_id,source_plan_item_id,debt_type,media_item_id,title,original_seconds,remaining_seconds,baseline_completed_seconds,status,reason,opened_on,created_at,updated_at) "
                + "values ('debt-quiz','user-1',:itemId,'QUIZ','media-1','资料分析',600,600,0,'OPEN','DAY_END','2026-08-29',1,1)")
        .param("itemId", sourceItemId)
        .update();
  }

  private long count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Long.class).single();
  }

  private String value(String sql) {
    return jdbc.sql(sql).query(String.class).single();
  }
}
