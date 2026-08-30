package com.shangan.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.exam.application.ExamGoalService;
import com.shangan.planning.application.DailyPlanService;
import com.shangan.quiz.application.QuizService;
import com.shangan.reporting.application.DailyReportService;
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

/** 场景 A 验收：考试目标、锁定计划、可信视频完成、答题、计划完成与日报形成完整闭环。 */
@SpringBootTest
@Import(FullLearningFlowAcceptanceTest.FixedClockConfiguration.class)
class FullLearningFlowAcceptanceTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);

  @TempDir static Path databaseDirectory;

  @Autowired ExamGoalService exams;
  @Autowired DailyPlanService plans;
  @Autowired QuizService quizzes;
  @Autowired DailyReportService reports;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("full-learning-flow.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
  }

  @BeforeEach
  void setUp() {
    insertUserCourseLessonAndQuestion();
  }

  @Test
  void completesFrozenV1LearningLoop() {
    exams.saveGoal(
        "user-1",
        "Asia/Shanghai",
        "公务员考试",
        LocalDate.of(2026, 12, 1),
        LocalDate.of(2026, 11, 1),
        30,
        List.of("course-1"));

    var draft =
        plans.addItem(
            "user-1",
            TODAY,
            new DailyPlanService.ItemDraft("VIDEO", "由服务端读取标题", "media-1", null, 1, 0));
    String itemId = draft.items().getFirst().id();
    var locked = plans.lock("user-1", TODAY);
    assertThat(locked.items().getFirst().quizRequired()).isTrue();

    // 学习模块在可信阈值达成后写入视频进度，再通过显式端口推动计划，不接受客户端完成字段。
    insertCompletedTrustedProgressAndSession(itemId);
    plans.updateVideoWatchProgress("user-1", itemId, 600, true);
    quizzes.submit(
        "user-1",
        "media-1",
        new QuizService.AttemptCommand(
            itemId,
            15_000,
            List.of(new QuizService.AnswerCommand("question-1", "option-b", 15_000))));

    var report = reports.generate("user-1", TODAY);
    assertThat(value("select status from daily_plans")).isEqualTo("COMPLETED");
    assertThat(value("select status from daily_plan_items")).isEqualTo("COMPLETED");
    assertThat(report.videoStudySeconds()).isEqualTo(600);
    assertThat(report.answerCount()).isEqualTo(1);
    assertThat(report.answerAccuracy()).isEqualTo(100);
    assertThat(report.completionRate()).isEqualTo(100);
    assertThat(exams.progress("user-1").completedLessons()).isEqualTo(1);
  }

  private void insertUserCourseLessonAndQuestion() {
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
            "insert into questions (id,media_item_id,question_type,content,explanation,enabled,sort_order,created_at,updated_at) "
                + "values ('question-1','media-1','SINGLE_CHOICE','正确答案是什么','解析',1,0,1,1)")
        .update();
    jdbc.sql(
            "insert into question_options (id,question_id,content,correct,sort_order) values "
                + "('option-a','question-1','A',0,0),('option-b','question-1','B',1,1)")
        .update();
  }

  private void insertCompletedTrustedProgressAndSession(String itemId) {
    long now = NOW.toEpochMilli();
    jdbc.sql(
            "insert into video_progress (id,user_id,media_item_id,max_verified_position_ms,verified_watch_ms,completed_at,last_watched_at,created_at,updated_at) "
                + "values ('progress-1','user-1','media-1',600000,600000,:now,:now,:now,:now)")
        .param("now", now)
        .update();
    jdbc.sql(
            """
            insert into watch_sessions (
              id,user_id,media_item_id,emby_item_id,plan_item_id,device_id,status,
              play_session_id,upstream_path,hls,duration_ms,started_position_ms,
              last_reported_position_ms,max_verified_position_ms,verified_watch_ms,last_sequence,
              last_heartbeat_at,alive_check_pending,started_at,ended_at,created_at,updated_at
            ) values (
              'session-1','user-1','media-1','emby-1',:itemId,'iphone','COMPLETED',
              'play-1','/Videos/emby-1/stream',0,600000,0,
              600000,600000,600000,60,:now,0,:now,:now,:now,:now
            )
            """)
        .param("itemId", itemId)
        .param("now", now)
        .update();
  }

  private String value(String sql) {
    return jdbc.sql(sql).query(String.class).single();
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
