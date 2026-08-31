package com.shangan.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.catalog.application.LessonStudyContentImportService;
import com.shangan.common.api.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

/** 验证课程归属、整包事务、重复覆盖和导入时间语义。 */
@SpringBootTest
@Import(LessonStudyContentImportIntegrationTest.FixedClockConfiguration.class)
class LessonStudyContentImportIntegrationTest {

  private static final Instant IMPORT_TIME = Instant.parse("2026-08-31T03:00:00Z");

  @TempDir static Path databaseDirectory;

  @Autowired LessonStudyContentImportService service;
  @Autowired JdbcClient jdbc;

  /** 为测试使用独立 SQLite 文件和合法 JWT 测试密钥。 */
  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("lesson-content-import.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  /** 每个用例重建两门课程和三集课时，避免测试之间共享业务状态。 */
  @BeforeEach
  void prepareCatalog() {
    jdbc.sql("delete from lesson_study_contents").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    insertCourse("course-1", "parent-1");
    insertCourse("course-2", "parent-2");
    insertLesson("lesson-1", "course-1", "emby-1");
    insertLesson("lesson-2", "course-1", "emby-2");
    insertLesson("lesson-3", "course-2", "emby-3");
  }

  /** 合法包一次写入全部课时，重复导入覆盖内容但保留首次导入时间。 */
  @Test
  void importsCompletePackageAndOverwritesExistingContent() throws IOException {
    service.importZip(
        "course-1",
        packageZip(
            Map.of(
                "emby-1", new TextPair("全文一", "# 摘要一"),
                "emby-2", new TextPair("全文二", "# 摘要二"))));

    assertThat(jdbc.sql("select count(*) from lesson_study_contents").query(Integer.class).single())
        .isEqualTo(2);
    jdbc.sql(
            "update lesson_study_contents set imported_at = 123, updated_at = 123 "
                + "where media_item_id = 'lesson-1'")
        .update();

    service.importZip("course-1", packageZip(Map.of("emby-1", new TextPair("新全文", "# 新摘要"))));

    Map<String, Object> updated =
        jdbc.sql(
                "select full_text, summary_markdown, imported_at, updated_at "
                    + "from lesson_study_contents where media_item_id = 'lesson-1'")
            .query()
            .singleRow();
    assertThat(updated.get("full_text")).isEqualTo("新全文");
    assertThat(updated.get("summary_markdown")).isEqualTo("# 新摘要");
    assertThat(((Number) updated.get("imported_at")).longValue()).isEqualTo(123L);
    assertThat(((Number) updated.get("updated_at")).longValue())
        .isEqualTo(IMPORT_TIME.toEpochMilli());
  }

  /** 包含其他课程课时时必须在事务前拒绝，并保持数据库零写入。 */
  @Test
  void rejectsLessonOutsideCurrentCourseWithoutWriting() throws IOException {
    byte[] zip = packageZip(Map.of("emby-3", new TextPair("其他课程全文", "# 其他摘要")));

    assertThatThrownBy(() -> service.importZip("course-1", zip))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.errorCode()).isEqualTo("STUDY_CONTENT_IMPORT_INVALID");
              assertThat(error.getMessage()).contains("emby-3").contains("当前课程");
            });
    assertThat(jdbc.sql("select count(*) from lesson_study_contents").query(Integer.class).single())
        .isZero();
  }

  /** 第二条写入由数据库触发失败时，第一条也必须随同一事务回滚。 */
  @Test
  void rollsBackCompletePackageWhenAnyWriteFails() throws IOException {
    jdbc.sql(
            "create trigger fail_second_content before insert on lesson_study_contents "
                + "when new.media_item_id = 'lesson-2' begin "
                + "select raise(abort, 'forced failure'); end")
        .update();
    byte[] zip =
        packageZip(
            Map.of(
                "emby-1", new TextPair("全文一", "# 摘要一"),
                "emby-2", new TextPair("全文二", "# 摘要二")));

    assertThatThrownBy(() -> service.importZip("course-1", zip))
        .isInstanceOf(RuntimeException.class);
    assertThat(jdbc.sql("select count(*) from lesson_study_contents").query(Integer.class).single())
        .isZero();
    jdbc.sql("drop trigger fail_second_content").update();
  }

  /** 插入一门最小课程快照。 */
  private void insertCourse(String id, String parentId) {
    jdbc.sql(
            "insert into courses (id, name, description, emby_parent_item_id, enabled, sort_order, "
                + "created_at, updated_at) values (:id, :id, '', :parentId, 1, 0, 1, 1)")
        .param("id", id)
        .param("parentId", parentId)
        .update();
  }

  /** 插入一集用于 Emby Item ID 精确匹配的课时快照。 */
  private void insertLesson(String id, String courseId, String embyItemId) {
    jdbc.sql(
            "insert into media_items (id, course_id, emby_item_id, title, duration_ms, enabled, "
                + "sort_order, available, created_at, updated_at) values "
                + "(:id, :courseId, :embyItemId, :id, 60000, 1, 0, 1, 1, 1)")
        .param("id", id)
        .param("courseId", courseId)
        .param("embyItemId", embyItemId)
        .update();
  }

  /** 按设计中的固定 manifest 和目录结构生成测试 ZIP。 */
  private byte[] packageZip(Map<String, TextPair> lessons) throws IOException {
    Map<String, TextPair> ordered = new LinkedHashMap<>(lessons);
    String manifestLessons =
        ordered.keySet().stream()
            .map(id -> "{\"embyItemId\":\"" + id + "\"}")
            .collect(java.util.stream.Collectors.joining(","));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      writeEntry(
          zip,
          "manifest.json",
          ("{\"version\":1,\"lessons\":[" + manifestLessons + "]}")
              .getBytes(StandardCharsets.UTF_8));
      for (Map.Entry<String, TextPair> lesson : ordered.entrySet()) {
        writeEntry(
            zip,
            "lessons/" + lesson.getKey() + "/transcript.txt",
            lesson.getValue().fullText().getBytes(StandardCharsets.UTF_8));
        writeEntry(
            zip,
            "lessons/" + lesson.getKey() + "/summary.md",
            lesson.getValue().summary().getBytes(StandardCharsets.UTF_8));
      }
    }
    return bytes.toByteArray();
  }

  /** 写入一个 ZIP 测试条目。 */
  private void writeEntry(ZipOutputStream zip, String path, byte[] content) throws IOException {
    zip.putNextEntry(new ZipEntry(path));
    zip.write(content);
    zip.closeEntry();
  }

  /** 测试使用的全文和摘要组合。 */
  private record TextPair(String fullText, String summary) {}

  /** 通过主 Bean 覆盖系统时钟，使导入时间断言完全确定。 */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {

    /** 提供固定 UTC 时间。 */
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(IMPORT_TIME, ZoneOffset.UTC);
    }
  }
}
