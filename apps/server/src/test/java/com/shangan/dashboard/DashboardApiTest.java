package com.shangan.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** 验证考试目标写入、用户隔离和首页聚合 DTO 的基础契约。 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir static Path databaseDirectory;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("dashboard.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUpCatalog() {
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
            ) values (
              'user-1', 'alice', 'unused-test-hash', 'Alice', 'USER', 'Asia/Shanghai',
              'NORMAL', '23:59', 1, 1, 1
            )
            """)
        .update();
    jdbc.sql(
            """
            insert into courses (
              id, name, description, emby_parent_item_id, enabled, sort_order,
              created_at, updated_at
            ) values ('course-1', '行测', '', 'emby-parent-1', 1, 0, 1, 1)
            """)
        .update();
    jdbc.sql(
            """
            insert into media_items (
              id, course_id, emby_item_id, title, duration_ms, enabled,
              sort_order, available, created_at, updated_at
            ) values ('lesson-1', 'course-1', 'emby-1', '资料分析', 60000, 1, 0, 1, 1, 1)
            """)
        .update();
  }

  @Test
  void savesExamGoalAndReturnsDashboardBaseline() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/exam-goal")
                .with(userJwt("user-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "name", "2026 国考",
                            "examDate", "2026-11-01",
                            "targetCompletionDate", "2026-10-18",
                            "reviewBufferDays", 14,
                            "courseIds", java.util.List.of("course-1")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("2026 国考"));

    mockMvc
        .perform(get("/api/v1/dashboard").with(userJwt("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exam.name").value("2026 国考"))
        .andExpect(jsonPath("$.progressPressure.totalLessons").value(1))
        .andExpect(jsonPath("$.progressPressure.remainingLessons").value(1))
        .andExpect(jsonPath("$.todayPlan.status").value("NONE"))
        .andExpect(jsonPath("$.openDebtSeconds").value(0))
        .andExpect(jsonPath("$.studyTodaySeconds").value(0))
        .andExpect(jsonPath("$.answerAccuracy").value(0));
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(String userId) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(userId)
                    .claim("username", "alice")
                    .claim("role", "USER")
                    .claim("timezone", "Asia/Shanghai"));
  }
}
