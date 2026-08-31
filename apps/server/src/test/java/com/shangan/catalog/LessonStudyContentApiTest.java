package com.shangan.catalog;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 验证 App 只能读取启用且可用课时的人工导入全文和摘要。 */
@SpringBootTest
@AutoConfigureMockMvc
class LessonStudyContentApiTest {

  @TempDir static Path databaseDirectory;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbc;

  /** 使用独立 SQLite 数据库，避免 API 契约测试污染其他用例。 */
  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("lesson-study-content-api.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  /** 每个用例准备一门课程、一集可见课时和一份确定时间的学习内容。 */
  @BeforeEach
  void prepareContent() {
    jdbc.sql("delete from lesson_study_contents").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql(
            """
            insert into courses (
              id, name, description, emby_parent_item_id, enabled, sort_order, created_at, updated_at
            ) values ('course-1', '行测', '', 'parent-1', 1, 0, 1, 1)
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
    jdbc.sql(
            """
            insert into lesson_study_contents (
              id, media_item_id, full_text, summary_markdown,
              transcript_updated_at, summary_updated_at, imported_at, updated_at
            ) values (
              'content-1', 'lesson-1', '完整全文', '# 摘要',
              :updatedAt, :updatedAt, 1, :updatedAt
            )
            """)
        .param("updatedAt", Instant.parse("2026-08-31T03:00:00Z").toEpochMilli())
        .update();
  }

  /** 已登录用户读取内容时返回直接 DTO，并把数据库毫秒时间转换为 UTC ISO-8601。 */
  @Test
  void returnsImportedStudyContent() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/lessons/lesson-1/study-content")
                .with(jwt().jwt(token -> token.subject("user-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lessonId").value("lesson-1"))
        .andExpect(jsonPath("$.fullText").value("完整全文"))
        .andExpect(jsonPath("$.summaryMarkdown").value("# 摘要"))
        .andExpect(jsonPath("$.transcriptStatus").value("READY"))
        .andExpect(jsonPath("$.summaryStatus").value("READY"))
        .andExpect(jsonPath("$.updatedAt").value("2026-08-31T03:00:00Z"));
  }

  /** 转写先完成时接口应明确表达摘要仍缺失，而不是把整份内容判为不存在。 */
  @Test
  void returnsPartiallyReadyTranscript() throws Exception {
    jdbc.sql(
            "update lesson_study_contents set summary_markdown=null, summary_updated_at=null "
                + "where media_item_id='lesson-1'")
        .update();

    mockMvc
        .perform(
            get("/api/v1/lessons/lesson-1/study-content")
                .with(jwt().jwt(token -> token.subject("user-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transcriptStatus").value("READY"))
        .andExpect(jsonPath("$.summaryStatus").value("MISSING"))
        .andExpect(jsonPath("$.summaryMarkdown").doesNotExist());
  }

  /** 内容缺失和课时不可见分别返回稳定错误码，且不会泄露被禁用课时内容。 */
  @Test
  void rejectsMissingContentAndUnavailableLesson() throws Exception {
    jdbc.sql("delete from lesson_study_contents").update();
    mockMvc
        .perform(
            get("/api/v1/lessons/lesson-1/study-content")
                .with(jwt().jwt(token -> token.subject("user-1"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("LESSON_STUDY_CONTENT_NOT_FOUND"));

    jdbc.sql("update media_items set enabled = 0 where id = 'lesson-1'").update();
    mockMvc
        .perform(
            get("/api/v1/lessons/lesson-1/study-content")
                .with(jwt().jwt(token -> token.subject("user-1"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("LESSON_NOT_FOUND"));
  }
}
