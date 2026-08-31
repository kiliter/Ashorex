package com.shangan.ai.content.application;

import com.shangan.ai.content.domain.ContentGenerationJob;
import com.shangan.ai.content.infrastructure.ContentGenerationJobRepository;
import com.shangan.ai.content.infrastructure.QuizGenerationDraftRepository;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.catalog.infrastructure.LessonStudyContentRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 创建单课时、课程批量和失败重试任务；真正外部调用由全局串行 Worker 执行。 */
@Service
public class ContentGenerationJobService {

  private final ContentGenerationJobRepository jobs;
  private final CourseRepository courses;
  private final LessonStudyContentRepository contents;
  private final QuizGenerationDraftRepository drafts;
  private final IntegrationSettingsProvider settings;
  private final IdGenerator ids;
  private final Clock clock;

  public ContentGenerationJobService(
      ContentGenerationJobRepository jobs,
      CourseRepository courses,
      LessonStudyContentRepository contents,
      QuizGenerationDraftRepository drafts,
      IntegrationSettingsProvider settings,
      IdGenerator ids,
      Clock clock) {
    this.jobs = jobs;
    this.courses = courses;
    this.contents = contents;
    this.drafts = drafts;
    this.settings = settings;
    this.ids = ids;
    this.clock = clock;
  }

  /** 单课时按钮只排队，不等待音频、ASR 或 LLM 响应。 */
  public EnqueueResult enqueueLesson(
      String lessonId,
      ContentGenerationJob.Type type,
      boolean overwriteExisting,
      int requestedQuestionCount,
      String createdBy) {
    MediaItem lesson =
        courses
            .findMediaItem(lessonId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "LESSON_NOT_FOUND", "课时不存在"));
    return enqueue(lesson, type, overwriteExisting, requestedQuestionCount, createdBy);
  }

  /** 课程批量操作按 Repository 的课时排序稳定创建任务，并统计已有内容和重复任务。 */
  public BatchEnqueueResult enqueueCourse(
      String courseId,
      ContentGenerationJob.Type type,
      int requestedQuestionCount,
      String createdBy) {
    courses
        .findCourse(courseId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
    List<String> jobIds = new ArrayList<>();
    int skipped = 0;
    for (MediaItem lesson : courses.findMediaItems(courseId, false)) {
      try {
        EnqueueResult result = enqueue(lesson, type, false, requestedQuestionCount, createdBy);
        if (result.created()) jobIds.add(result.jobId());
        else skipped++;
      } catch (BusinessException exception) {
        if ("TRANSCRIPT_NOT_READY".equals(exception.errorCode())) skipped++;
        else throw exception;
      }
    }
    return new BatchEnqueueResult(List.copyOf(jobIds), skipped);
  }

  /** 失败任务以新 ID 和递增 attempt 重新排队，旧任务及日志保留用于审计。 */
  public EnqueueResult retry(String jobId, String createdBy) {
    ContentGenerationJob failed =
        jobs.findById(jobId)
            .orElseThrow(
                () ->
                    new BusinessException(HttpStatus.NOT_FOUND, "CONTENT_JOB_NOT_FOUND", "任务不存在"));
    if (failed.status() != ContentGenerationJob.Status.FAILED) {
      throw new BusinessException(HttpStatus.CONFLICT, "CONTENT_JOB_NOT_FAILED", "只有失败任务可以重试");
    }
    ContentGenerationJob retried =
        newJob(
            failed.courseId(),
            failed.mediaItemId(),
            failed.type(),
            true,
            failed.requestedQuestionCount(),
            failed.attempt() + 1,
            createdBy);
    jobs.insert(retried);
    return new EnqueueResult(true, retried.id(), "已重新排队");
  }

  public List<ContentGenerationJob> recent(String courseId, String type, String status, int limit) {
    return jobs.findRecent(courseId, type, status, limit);
  }

  public ContentGenerationJob detail(String jobId) {
    return jobs.findById(jobId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "CONTENT_JOB_NOT_FOUND", "任务不存在"));
  }

  public List<ContentGenerationJob.Log> logs(String jobId) {
    detail(jobId);
    return jobs.findLogs(jobId);
  }

  public ContentGenerationJobRepository.QueueStats stats() {
    return jobs.stats(clock.instant().minusSeconds(86_400));
  }

  private EnqueueResult enqueue(
      MediaItem lesson,
      ContentGenerationJob.Type type,
      boolean overwriteExisting,
      int requestedQuestionCount,
      String createdBy) {
    var content = contents.findByMediaItemId(lesson.id());
    if (type == ContentGenerationJob.Type.TRANSCRIBE
        && !overwriteExisting
        && content.filter(value -> value.transcriptReady()).isPresent()) {
      return new EnqueueResult(false, null, "已有全文，已跳过");
    }
    if (type != ContentGenerationJob.Type.TRANSCRIBE
        && content.filter(value -> value.transcriptReady()).isEmpty()) {
      throw new BusinessException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_READY", "请先完成该课时全文转写");
    }
    if (type == ContentGenerationJob.Type.SUMMARIZE
        && !overwriteExisting
        && content.filter(value -> value.summaryReady()).isPresent()) {
      return new EnqueueResult(false, null, "已有摘要，已跳过");
    }
    if (type == ContentGenerationJob.Type.GENERATE_QUIZ
        && !overwriteExisting
        && drafts.hasReadyDraft(lesson.id())) {
      return new EnqueueResult(false, null, "已有待审核题目草稿，已跳过");
    }
    ContentGenerationJob job =
        newJob(
            lesson.courseId(),
            lesson.id(),
            type,
            overwriteExisting,
            requestedQuestionCount,
            1,
            createdBy);
    try {
      jobs.insert(job);
      return new EnqueueResult(true, job.id(), "任务已排队");
    } catch (DataIntegrityViolationException exception) {
      return new EnqueueResult(false, null, "同类型任务正在排队或执行，已跳过");
    }
  }

  private ContentGenerationJob newJob(
      String courseId,
      String lessonId,
      ContentGenerationJob.Type type,
      boolean overwriteExisting,
      int requestedQuestionCount,
      int attempt,
      String createdBy) {
    if (requestedQuestionCount < 1 || requestedQuestionCount > 20) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "QUIZ_QUESTION_COUNT_INVALID", "题目数量必须在 1 到 20 之间");
    }
    RuntimeIntegrationSettings snapshot = settings.current();
    Instant now = clock.instant();
    return new ContentGenerationJob(
        ids.nextId(),
        courseId,
        lessonId,
        type,
        ContentGenerationJob.Status.QUEUED,
        requestedQuestionCount,
        overwriteExisting,
        now,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        type == ContentGenerationJob.Type.TRANSCRIBE ? snapshot.asr().model() : null,
        type == ContentGenerationJob.Type.TRANSCRIBE ? null : snapshot.llm().model(),
        type == ContentGenerationJob.Type.TRANSCRIBE ? null : snapshot.llm().contextLength(),
        type == ContentGenerationJob.Type.TRANSCRIBE ? null : snapshot.llm().maxCompletionTokens(),
        null,
        null,
        attempt,
        null,
        null,
        createdBy == null || createdBy.isBlank() ? "admin" : createdBy);
  }

  public record EnqueueResult(boolean created, String jobId, String message) {}

  public record BatchEnqueueResult(List<String> jobIds, int skippedCount) {
    public int createdCount() {
      return jobIds.size();
    }
  }
}
