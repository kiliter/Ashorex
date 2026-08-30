package com.shangan.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.quiz.application.QuizService;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 使用真实 SQLite 验证题目只能在可信完成视频后读取，且预提交响应不泄露答案。 */
@SpringBootTest
class QuizUnlockIntegrationTest {
  @TempDir static Path databaseDirectory;

  @Autowired QuizService quizzes;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("quiz-unlock.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    clearQuizFixtures();
    insertCatalogFixtures();
    insertQuestion("question-enabled", true, 0);
    insertQuestion("question-disabled", false, 1);
  }

  @Test
  void rejectsUnfinishedVideoThenReturnsOnlyEnabledSafeQuestions() {
    assertThatThrownBy(() -> quizzes.getQuiz("user-1", "media-1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error -> assertThat(((BusinessException) error).errorCode()).isEqualTo("QUIZ_LOCKED"));

    jdbc.sql(
            """
            insert into video_progress (
              id,user_id,media_item_id,max_verified_position_ms,verified_watch_ms,
              completed_at,last_watched_at,created_at,updated_at
            ) values ('progress-1','user-1','media-1',600000,600000,:now,:now,:now,:now)
            """)
        .param("now", Instant.parse("2026-08-30T01:00:00Z").toEpochMilli())
        .update();

    QuizService.QuizView quiz = quizzes.getQuiz("user-1", "media-1");

    assertThat(quiz.questions()).hasSize(1);
    assertThat(quiz.questions().getFirst().id()).isEqualTo("question-enabled");
    assertThat(quiz.questions().getFirst().options())
        .extracting(QuizService.OptionView::id)
        .containsExactly("question-enabled-a", "question-enabled-b");
    assertThat(quiz.questions().getFirst().toString()).doesNotContain("解析", "correct");
  }

  private void clearQuizFixtures() {
    jdbc.sql("delete from quiz_answers").update();
    jdbc.sql("delete from quiz_attempts").update();
    jdbc.sql("delete from question_options").update();
    jdbc.sql("delete from questions").update();
    jdbc.sql("delete from alive_checks").update();
    jdbc.sql("delete from watch_sessions").update();
    jdbc.sql("delete from video_progress").update();
    jdbc.sql("delete from debt_repayments").update();
    jdbc.sql("delete from learning_debts").update();
    jdbc.sql("delete from daily_plan_items").update();
    jdbc.sql("delete from daily_plans").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql("delete from refresh_tokens").update();
    jdbc.sql("delete from users").update();
  }

  private void insertCatalogFixtures() {
    jdbc.sql(
            """
            insert into users (
              id,username,password_hash,display_name,role,timezone,
              alive_check_level,day_end_local_time,enabled,created_at,updated_at
            ) values ('user-1','learner','x','学习者','USER','Asia/Shanghai','OFF','23:59',1,1,1)
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

  private void insertQuestion(String id, boolean enabled, int sortOrder) {
    jdbc.sql(
            "insert into questions "
                + "(id,media_item_id,question_type,content,explanation,enabled,sort_order,created_at,updated_at) "
                + "values (:id,'media-1','SINGLE_CHOICE','请选择正确答案','这是解析',:enabled,:sortOrder,1,1)")
        .param("id", id)
        .param("enabled", enabled ? 1 : 0)
        .param("sortOrder", sortOrder)
        .update();
    jdbc.sql(
            "insert into question_options (id,question_id,content,correct,sort_order) values "
                + "(:a,:id,'A',0,0),(:b,:id,'B',1,1)")
        .param("a", id + "-a")
        .param("b", id + "-b")
        .param("id", id)
        .update();
  }
}
