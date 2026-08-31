package com.shangan.ai.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

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

/** 验证 SQLite 队列排序和服务重启恢复规则。 */
@SpringBootTest
class ContentGenerationJobRepositoryIntegrationTest {

  @TempDir static Path databaseDirectory;

  @Autowired ContentGenerationJobRepository jobs;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("content-job-repository.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.content.worker-delay-ms", () -> "3600000");
    registry.add("app.content.auto-fill-tick-ms", () -> "3600000");
  }

  @BeforeEach
  void prepare() {
    jdbc.sql("delete from content_generation_job_logs").update();
    jdbc.sql("delete from content_generation_jobs").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    insertCourse("course-b", "B 课程", 20);
    insertCourse("course-a", "A 课程", 10);
    insertLesson("lesson-2", "course-a", 20);
    insertLesson("lesson-1", "course-a", 10);
    insertLesson("lesson-3", "course-b", 1);
  }

  /** 同一排队时间时先按课程排序，再按课时排序，确保批量执行结果稳定。 */
  @Test
  void selectsQueuedJobByStableCourseAndLessonOrder() {
    insertJob("job-b", "course-b", "lesson-3", "QUEUED");
    insertJob("job-a2", "course-a", "lesson-2", "QUEUED");
    insertJob("job-a1", "course-a", "lesson-1", "QUEUED");

    assertThat(jobs.findNextQueued()).get().extracting(value -> value.id()).isEqualTo("job-a1");
  }

  /** 重启只终止执行态，排队和已完成任务均保持原状。 */
  @Test
  void marksOnlyRunningJobsAsServerRestarted() {
    insertJob("queued", "course-a", "lesson-1", "QUEUED");
    insertJob("running", "course-a", "lesson-2", "SUMMARIZING");
    Instant finishedAt = Instant.parse("2026-08-31T08:00:00Z");

    assertThat(jobs.failInterrupted(finishedAt)).isEqualTo(1);

    assertThat(jobs.findById("queued"))
        .get()
        .extracting(value -> value.status().name())
        .isEqualTo("QUEUED");
    assertThat(jobs.findById("running"))
        .get()
        .satisfies(
            value -> {
              assertThat(value.status().name()).isEqualTo("FAILED");
              assertThat(value.errorCode()).isEqualTo("SERVER_RESTARTED");
              assertThat(value.finishedAt()).isEqualTo(finishedAt);
            });
  }

  private void insertCourse(String id, String name, int sortOrder) {
    jdbc.sql(
            "insert into courses (id,name,description,emby_parent_item_id,enabled,sort_order,created_at,updated_at) values (:id,:name,'',:id,1,:sortOrder,1,1)")
        .param("id", id)
        .param("name", name)
        .param("sortOrder", sortOrder)
        .update();
  }

  private void insertLesson(String id, String courseId, int sortOrder) {
    jdbc.sql(
            "insert into media_items (id,course_id,emby_item_id,title,duration_ms,enabled,sort_order,available,created_at,updated_at) values (:id,:courseId,:id,:id,60000,1,:sortOrder,1,1,1)")
        .param("id", id)
        .param("courseId", courseId)
        .param("sortOrder", sortOrder)
        .update();
  }

  private void insertJob(String id, String courseId, String lessonId, String status) {
    jdbc.sql(
            "insert into content_generation_jobs (id,course_id,media_item_id,job_type,status,requested_question_count,overwrite_existing,queued_at,attempt,created_by) values (:id,:courseId,:lessonId,'SUMMARIZE',:status,5,0,100,1,'test')")
        .param("id", id)
        .param("courseId", courseId)
        .param("lessonId", lessonId)
        .param("status", status)
        .update();
  }
}
