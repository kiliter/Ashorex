package com.shangan.ai.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.ai.content.domain.QuizGenerationDraft;
import com.shangan.ai.content.infrastructure.QuizGenerationDraftRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证课程级草稿批量发布只追加正式题目，且重复提交保持幂等。 */
@SpringBootTest
class QuizDraftPublishIntegrationTest {

  @TempDir static Path databaseDirectory;

  @Autowired JdbcClient jdbc;
  @Autowired QuizGenerationDraftRepository drafts;
  @Autowired QuizDraftPublishService publishing;
  @Autowired ContentGenerationJobService contentJobs;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("quiz-draft-publish.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @BeforeEach
  void prepare() {
    jdbc.sql("delete from quiz_generation_draft_options").update();
    jdbc.sql("delete from quiz_generation_draft_items").update();
    jdbc.sql("delete from quiz_generation_drafts").update();
    jdbc.sql("delete from content_generation_jobs").update();
    jdbc.sql("delete from question_options").update();
    jdbc.sql("delete from questions").update();
    jdbc.sql("delete from lesson_study_contents").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql(
            "insert into courses "
                + "(id,name,description,emby_parent_item_id,enabled,sort_order,created_at,updated_at) "
                + "values ('course-1','课程','','parent-1',1,0,1,1)")
        .update();
    jdbc.sql(
            "insert into media_items "
                + "(id,course_id,emby_item_id,title,duration_ms,enabled,sort_order,available,created_at,updated_at) "
                + "values ('lesson-1','course-1','emby-1','课时',60000,1,0,1,1,1)")
        .update();
    jdbc.sql(
            "insert into content_generation_jobs "
                + "(id,course_id,media_item_id,job_type,status,queued_at,attempt,created_by) "
                + "values ('job-1','course-1','lesson-1','GENERATE_QUIZ','READY_FOR_REVIEW',1,1,'admin')")
        .update();
    jdbc.sql(
            "insert into lesson_study_contents "
                + "(id,media_item_id,full_text,summary_markdown,transcript_updated_at,summary_updated_at,updated_at) "
                + "values ('content-1','lesson-1','已有全文','已有摘要',1,1,1)")
        .update();
    drafts.save(draft());
  }

  @Test
  void publishesValidatedDraftOnce() {
    var first = publishing.publish("course-1", List.of("draft-1"));
    var second = publishing.publish("course-1", List.of("draft-1"));

    assertThat(first.publishedQuestionCount()).isEqualTo(2);
    assertThat(second.publishedQuestionCount()).isZero();
    assertThat(jdbc.sql("select count(*) from questions").query(Integer.class).single())
        .isEqualTo(2);
    assertThat(drafts.findById("draft-1").orElseThrow().status())
        .isEqualTo(QuizGenerationDraft.Status.PUBLISHED);
  }

  /** 批量 AI 不得为已经发布过题目的课时再次排入任何阶段。 */
  @Test
  void batchWorkflowSkipsPublishedQuizGeneration() {
    publishing.publish("course-1", List.of("draft-1"));

    var result = contentJobs.enqueueLessonsWorkflow("course-1", List.of("lesson-1"), 5, "admin");

    assertThat(result.createdCount()).isZero();
    assertThat(result.skippedCount()).isEqualTo(3);
  }

  private QuizGenerationDraft draft() {
    return new QuizGenerationDraft(
        "draft-1",
        "job-1",
        "course-1",
        "lesson-1",
        QuizGenerationDraft.Status.READY_FOR_REVIEW,
        2,
        Instant.ofEpochMilli(1),
        null,
        List.of(
            new QuizGenerationDraft.Item(
                "item-1",
                "SINGLE_CHOICE",
                "下列哪项正确？",
                "答案 A 正确。",
                0,
                null,
                List.of(
                    new QuizGenerationDraft.Option("option-1", "A", true, 0),
                    new QuizGenerationDraft.Option("option-2", "B", false, 1))),
            new QuizGenerationDraft.Item(
                "item-2",
                "TRUE_FALSE",
                "该说法正确吗？",
                "该说法正确。",
                1,
                null,
                List.of(
                    new QuizGenerationDraft.Option("option-3", "正确", true, 0),
                    new QuizGenerationDraft.Option("option-4", "错误", false, 1)))));
  }
}
