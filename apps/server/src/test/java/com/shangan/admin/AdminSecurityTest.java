package com.shangan.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.catalog.application.CatalogSnapshotWriter;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.quiz.application.QuizService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 验证管理后台使用独立 Session 认证、ADMIN 权限和 CSRF 防护。 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminSecurityTest {

  @TempDir static Path databaseDirectory;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbc;
  @Autowired CourseSyncService courses;
  @Autowired CatalogSnapshotWriter catalogSnapshotWriter;
  @Autowired QuizService quizzes;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("admin-security.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "admin");
    registry.add("app.security.bootstrap-admin-password", () -> "admin-test-password");
  }

  @Test
  void loginPageIsPublic() throws Exception {
    mockMvc.perform(get("/admin/login")).andExpect(status().isOk());
  }

  @Test
  void adminStylesheetIsPublicAndPagesUseSharedResponsiveLayout() throws Exception {
    mockMvc
        .perform(get("/assets/admin.css"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/css"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("--accent")));

    mockMvc
        .perform(get("/admin/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/assets/admin.css")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("login-shell")));

    mockMvc
        .perform(get("/admin/health").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("admin-shell")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"管理功能\"")));
  }

  @Test
  void allAdminTemplatesRenderWithRepresentativeContent() throws Exception {
    var course = courses.createCourse("模板测试课程", "用于覆盖后台页面渲染", "emby-parent-ui-test");
    catalogSnapshotWriter.apply(
        course.id(), List.of(new EmbyDtos.MediaItem("emby-lesson-ui-test", "模板测试课时", 600_000, 1)));
    var lesson = courses.listAdminLessons(course.id()).getFirst();
    var question =
        quizzes.saveQuestion(
            new QuizService.AdminQuestionCommand(
                null,
                lesson.id(),
                "TRUE_FALSE",
                "这是一道模板渲染测试题吗？",
                "用于验证后台页面可以正确渲染。",
                true,
                0,
                List.of(
                    new QuizService.AdminOptionCommand(null, "是", true, 0),
                    new QuizService.AdminOptionCommand(null, "否", false, 1))));

    var admin = user("admin").roles("ADMIN");
    mockMvc.perform(get("/admin/courses").with(admin)).andExpect(status().isOk());
    mockMvc
        .perform(get("/admin/courses/{courseId}/lessons", course.id()).with(admin))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("模板测试课时")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("data-select-all-lessons")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("data-selected-lesson-count")));
    mockMvc
        .perform(get("/admin/lessons/{lessonId}/questions", lesson.id()).with(admin))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("模板渲染测试题")));
    mockMvc
        .perform(get("/admin/lessons/{lessonId}/questions/new", lesson.id()).with(admin))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/admin/lessons/{lessonId}/questions/{questionId}", lesson.id(), question.id())
                .with(admin))
        .andExpect(status().isOk());
    mockMvc.perform(get("/admin/content-jobs").with(admin)).andExpect(status().isOk());
    mockMvc
        .perform(get("/admin/lessons/{lessonId}/study-content", lesson.id()).with(admin))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("尚未生成全文")));
    mockMvc
        .perform(get("/admin/courses/{courseId}/quiz-drafts", course.id()).with(admin))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("暂无题目草稿")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-select-all-drafts")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("data-selected-draft-count")))
        // 批量按钮不能命名为 action，否则浏览器会把 form.action 遮蔽成 RadioNodeList。
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"action\""))))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("getAttribute(\"action\")")));
  }

  @Test
  void quizDraftBatchPublishAcceptsExplicitSelectedIds() throws Exception {
    var course = courses.createCourse("批量审核课程", "验证题目草稿批量通过", "emby-batch-review");
    catalogSnapshotWriter.apply(
        course.id(), List.of(new EmbyDtos.MediaItem("emby-batch-lesson", "批量审核课时", 600_000, 1)));
    var lesson = courses.listAdminLessons(course.id()).getFirst();

    // 构造一份可发布草稿，验证 Controller 能接收多个同名 draftId 参数。
    jdbc.sql(
            """
            insert into content_generation_jobs (
              id, course_id, media_item_id, job_type, status,
              requested_question_count, queued_at, attempt, created_by
            ) values (
              'admin-batch-job', :courseId, :lessonId, 'GENERATE_QUIZ', 'READY_FOR_REVIEW',
              1, 100, 1, 'admin'
            )
            """)
        .param("courseId", course.id())
        .param("lessonId", lesson.id())
        .update();
    jdbc.sql(
            """
            insert into quiz_generation_drafts (
              id, job_id, course_id, media_item_id, status, requested_question_count, created_at
            ) values (
              'admin-batch-draft', 'admin-batch-job', :courseId, :lessonId,
              'READY_FOR_REVIEW', 1, 100
            )
            """)
        .param("courseId", course.id())
        .param("lessonId", lesson.id())
        .update();
    jdbc.sql(
            """
            insert into quiz_generation_draft_items (
              id, draft_id, question_type, content, explanation, sort_order
            ) values (
              'admin-batch-item', 'admin-batch-draft', 'TRUE_FALSE',
              '批量通过是否成功？', '正确答案为成功。', 0
            )
            """)
        .update();
    jdbc.sql(
            """
            insert into quiz_generation_draft_options (
              id, draft_item_id, content, correct, sort_order
            ) values
              ('admin-batch-option-1', 'admin-batch-item', '成功', 1, 0),
              ('admin-batch-option-2', 'admin-batch-item', '失败', 0, 1)
            """)
        .update();

    mockMvc
        .perform(
            get("/admin/courses/{courseId}/quiz-drafts", course.id())
                .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("批量审核课时")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("quiz-card-grid")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("quiz-card__summary")));

    mockMvc
        .perform(
            post("/admin/courses/{courseId}/quiz-drafts/batch", course.id())
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .param("action", "APPROVE")
                .param("draftId", "admin-batch-draft")
                .accept(org.springframework.http.MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.action").value("APPROVE"))
        .andExpect(jsonPath("$.draftIds[0]").value("admin-batch-draft"));
  }

  @Test
  void protectedAdminPageRedirectsAnonymousUserToLogin() throws Exception {
    mockMvc.perform(get("/admin/health")).andExpect(status().is3xxRedirection());
  }

  @Test
  void normalUserCannotEnterAdminArea() throws Exception {
    mockMvc
        .perform(get("/admin/health").with(user("alice").roles("USER")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/login?denied=true"));
  }

  @Test
  void adminLoginPostRequiresCsrfToken() throws Exception {
    mockMvc
        .perform(
            post("/admin/login")
                .param("username", "admin")
                .param("password", "admin-test-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/login?denied=true"));
    mockMvc
        .perform(
            post("/admin/login")
                .with(csrf())
                .param("username", "admin")
                .param("password", "admin-test-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/health"));
  }

  @Test
  void adminLoginSessionIsPersistedInSqlite() throws Exception {
    long beforeLogin =
        jdbc.sql("select count(*) from spring_session where principal_name='admin'")
            .query(Long.class)
            .single();
    mockMvc
        .perform(
            post("/admin/login")
                .with(csrf())
                .param("username", "admin")
                .param("password", "admin-test-password"))
        .andExpect(status().is3xxRedirection());

    long persistedSessions =
        jdbc.sql("select count(*) from spring_session where principal_name='admin'")
            .query(Long.class)
            .single();

    org.assertj.core.api.Assertions.assertThat(persistedSessions).isGreaterThan(beforeLogin);
  }

  @Test
  void contentJobsLiveResponsesUseChineseLabelsAndSupportListRefresh() throws Exception {
    var course = courses.createCourse("任务刷新课程", "验证任务局部刷新", "emby-refresh-course");
    catalogSnapshotWriter.apply(
        course.id(), List.of(new EmbyDtos.MediaItem("emby-refresh-lesson", "任务刷新课时", 600_000, 1)));
    var lesson = courses.listAdminLessons(course.id()).getFirst();
    jdbc.sql(
            """
            insert into content_generation_jobs (
              id, course_id, media_item_id, job_type, status,
              requested_question_count, overwrite_existing, queued_at,
              finished_at, total_ms, attempt, error_code, error_message, created_by
            ) values (
              'admin-refresh-job', :courseId, :lessonId, 'TRANSCRIBE', 'FAILED',
              5, 0, 100, 200, 100, 1, 'ASR_UNAVAILABLE', '上游不可用', 'admin'
            )
            """)
        .param("courseId", course.id())
        .param("lessonId", lesson.id())
        .update();
    jdbc.sql(
            """
            insert into content_generation_job_logs (
              id, job_id, occurred_at, level, stage, message
            ) values ('admin-refresh-log', 'admin-refresh-job', 150, 'ERROR', 'FAILED', '任务失败')
            """)
        .update();
    // 同一排队时间的摘要与出题阶段用于验证三段任务会合并为一个工作流。
    jdbc.sql(
            """
            insert into content_generation_jobs (
              id, course_id, media_item_id, job_type, status,
              requested_question_count, overwrite_existing, queued_at,
              finished_at, total_ms, attempt, created_by
            ) values
              ('admin-refresh-summary', :courseId, :lessonId, 'SUMMARIZE', 'READY',
               5, 0, 100, 180, 80, 1, 'admin'),
              ('admin-refresh-quiz', :courseId, :lessonId, 'GENERATE_QUIZ', 'READY_FOR_REVIEW',
               5, 0, 100, 190, 90, 1, 'admin')
            """)
        .param("courseId", course.id())
        .param("lessonId", lesson.id())
        .update();

    var admin = user("admin").roles("ADMIN");
    mockMvc
        .perform(get("/admin/content-jobs/admin-refresh-job/live").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.job.status").value("FAILED"))
        .andExpect(jsonPath("$.job.statusLabel").value("失败"))
        .andExpect(jsonPath("$.job.typeLabel").value("转写全文"))
        .andExpect(jsonPath("$.job.queuedAtLabel").value("1970-01-01 08:00:00"))
        .andExpect(jsonPath("$.job.totalDurationLabel").value("100 ms"))
        .andExpect(jsonPath("$.logs[0].stageLabel").value("失败"));
    mockMvc
        .perform(
            get("/admin/content-jobs/live")
                .param("courseId", course.id())
                .param("status", "FAILED")
                .with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tasks.length()").value(1))
        .andExpect(jsonPath("$.tasks[0].statusLabel").value("存在失败"))
        .andExpect(jsonPath("$.tasks[0].courseName").value("任务刷新课程"))
        .andExpect(jsonPath("$.tasks[0].lessonTitle").value("任务刷新课时"))
        .andExpect(jsonPath("$.tasks[0].stageCount").value(3))
        .andExpect(jsonPath("$.tasks[0].stageProgressLabel").value("3 / 3"))
        .andExpect(jsonPath("$.stats.failedSince").isNumber());

    mockMvc
        .perform(
            get("/admin/content-jobs")
                .param("courseId", course.id())
                .param("status", "FAILED")
                .with(admin))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("主任务")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("子任务进度")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("全部状态")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("任务刷新课程")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("任务刷新课时")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<th>任务类型</th>"))));
    mockMvc
        .perform(get("/admin/content-jobs/courses/{courseId}", course.id()).with(admin))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("任务刷新课时")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("workflow-rail")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("转写全文")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("生成摘要")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("生成题目")));
    mockMvc
        .perform(get("/admin/content-jobs/courses/{courseId}/live", course.id()).with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflows.length()").value(1))
        .andExpect(jsonPath("$.workflows[0].lessonTitle").value("任务刷新课时"))
        .andExpect(jsonPath("$.workflows[0].stages[0].status").value("FAILED"))
        .andExpect(jsonPath("$.workflows[0].stages[1].status").value("READY"))
        .andExpect(jsonPath("$.workflows[0].stages[2].status").value("READY_FOR_REVIEW"));
    mockMvc
        .perform(
            get(
                    "/admin/content-jobs/workflows/{courseId}/{lessonId}/{queuedAt}/live",
                    course.id(),
                    lesson.id(),
                    100)
                .with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stages.length()").value(3))
        .andExpect(jsonPath("$.logs[0].jobTypeLabel").value("转写全文"))
        .andExpect(jsonPath("$.workflow.statusLabel").value("存在失败"));
  }

  @Test
  void adminCanCreateAndDisableNormalUserWithoutExposingSecrets() throws Exception {
    mockMvc
        .perform(
            post("/admin/users")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .param("username", "student.one")
                .param("displayName", "一号学员")
                .param("password", "strong-password-123"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/users"));

    String userId =
        jdbc.sql("select id from users where username='student.one'").query(String.class).single();
    mockMvc
        .perform(
            post("/admin/users/{id}/enabled", userId)
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .param("enabled", "false"))
        .andExpect(status().is3xxRedirection());

    mockMvc
        .perform(get("/admin/users").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("student.one")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("已禁用")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("strong-password-123"))));
  }

  @Test
  void healthPageShowsSafeOperationalStatusOnly() throws Exception {
    mockMvc
        .perform(get("/admin/health").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("SQLite 数据库")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Emby 媒体")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("test-jwt-secret"))));
  }
}
