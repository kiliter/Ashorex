package com.shangan.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 验证后台课程页的 ZIP 上传权限、CSRF、导入结果和状态展示。 */
@SpringBootTest
@AutoConfigureMockMvc
class LessonStudyContentAdminTest {

  @TempDir static Path databaseDirectory;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbc;

  /** 使用独立 SQLite 数据库和合法测试密钥。 */
  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("lesson-study-content-admin.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  /** 每次准备同一门课程和一集课时。 */
  @BeforeEach
  void prepareCatalog() {
    jdbc.sql("delete from lesson_study_contents").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql(
            "insert into courses (id, name, description, emby_parent_item_id, enabled, sort_order, "
                + "created_at, updated_at) values ('course-1', '行测', '', 'parent-1', 1, 0, 1, 1)")
        .update();
    jdbc.sql(
            "insert into media_items (id, course_id, emby_item_id, title, duration_ms, enabled, "
                + "sort_order, available, created_at, updated_at) values "
                + "('lesson-1', 'course-1', 'emby-1', '资料分析', 60000, 1, 0, 1, 1, 1)")
        .update();
  }

  /** 合法包必须要求 CSRF，成功后重定向并分别展示全文和摘要就绪状态。 */
  @Test
  void importsZipAndShowsLessonStatus() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "content.zip", "application/zip", validZip());
    var request =
        multipart("/admin/courses/course-1/study-content/import")
            .file(file)
            .with(user("admin").roles("ADMIN"));

    mockMvc.perform(request).andExpect(status().isForbidden());
    mockMvc
        .perform(request.with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/courses/course-1/lessons?imported=1"));

    mockMvc
        .perform(get("/admin/courses/course-1/lessons").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("全文已就绪")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("摘要已就绪")));
  }

  /** 非法包在当前课程页显示安全中文错误，且数据库不得留下部分内容。 */
  @Test
  void rendersSafeErrorForInvalidPackage() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "broken.zip", "application/zip", new byte[] {1, 2, 3});
    mockMvc
        .perform(
            multipart("/admin/courses/course-1/study-content/import")
                .file(file)
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ZIP")));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("select count(*) from lesson_study_contents").query(Integer.class).single())
        .isZero();
  }

  /** 创建一份符合冻结目录结构的最小 ZIP。 */
  private byte[] validZip() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      write(zip, "manifest.json", "{\"version\":1,\"lessons\":[{\"embyItemId\":\"emby-1\"}]}");
      write(zip, "lessons/emby-1/transcript.txt", "完整全文");
      write(zip, "lessons/emby-1/summary.md", "# 摘要");
    }
    return bytes.toByteArray();
  }

  /** 写入 UTF-8 ZIP 条目。 */
  private void write(ZipOutputStream zip, String path, String value) throws Exception {
    zip.putNextEntry(new ZipEntry(path));
    zip.write(value.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
