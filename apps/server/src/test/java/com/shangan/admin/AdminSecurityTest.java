package com.shangan.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        .andExpect(content().string(org.hamcrest.Matchers.containsString("模板测试课时")));
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
        .andExpect(content().string(org.hamcrest.Matchers.containsString("暂无题目草稿")));
  }

  @Test
  void protectedAdminPageRedirectsAnonymousUserToLogin() throws Exception {
    mockMvc.perform(get("/admin/health")).andExpect(status().is3xxRedirection());
  }

  @Test
  void normalUserCannotEnterAdminArea() throws Exception {
    mockMvc
        .perform(get("/admin/health").with(user("alice").roles("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminLoginPostRequiresCsrfToken() throws Exception {
    mockMvc
        .perform(
            post("/admin/login")
                .param("username", "admin")
                .param("password", "admin-test-password"))
        .andExpect(status().isForbidden());
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
