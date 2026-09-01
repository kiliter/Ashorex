package com.shangan.ai.content.infrastructure;

import com.shangan.ai.content.domain.ContentGenerationJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 内容生成队列、阶段日志和耗时的持久化边界。 */
public interface ContentGenerationJobRepository {

  void insert(ContentGenerationJob job);

  Optional<ContentGenerationJob> findNextQueued();

  Optional<ContentGenerationJob> findById(String jobId);

  List<ContentGenerationJob> findRecent(String courseId, String type, String status, int limit);

  /** 按课程汇总历史任务，供管理台第一层列表使用。 */
  List<CourseTaskSummary> summarizeByCourse();

  /** 将同一课时、同一排队时间创建的阶段任务汇总为一个工作流。 */
  List<WorkflowTaskSummary> summarizeWorkflows(String courseId, int limit);

  /** 精确读取一个工作流内的全部阶段，避免详情页依赖最近任务条数上限。 */
  List<ContentGenerationJob> findWorkflow(String courseId, String mediaItemId, Instant queuedAt);

  boolean transition(
      String jobId,
      ContentGenerationJob.Status expected,
      ContentGenerationJob.Status target,
      Instant occurredAt);

  void updateMetrics(String jobId, Metrics metrics);

  void fail(String jobId, String errorCode, String errorMessage, Instant finishedAt, long totalMs);

  int failInterrupted(Instant finishedAt);

  void addLog(ContentGenerationJob.Log log);

  List<ContentGenerationJob.Log> findLogs(String jobId);

  QueueStats stats(Instant since);

  record Metrics(
      Long audioDurationMs,
      Long fetchMs,
      Long transcribeMs,
      Long summarizeMs,
      Long quizGenerateMs,
      Long totalMs,
      Integer promptTokens,
      Integer completionTokens) {}

  /** 课程级任务汇总不包含正文、模型密钥或错误详情。 */
  record CourseTaskSummary(
      String courseId,
      long workflowCount,
      long taskCount,
      long queuedCount,
      long runningCount,
      long failedCount,
      Instant lastQueuedAt) {}

  /** 一个工作流最多包含转写、摘要和出题三个阶段。 */
  record WorkflowTaskSummary(
      String mediaItemId,
      Instant queuedAt,
      String transcribeJobId,
      String transcribeStatus,
      Long transcribeTotalMs,
      String summarizeJobId,
      String summarizeStatus,
      Long summarizeTotalMs,
      String quizJobId,
      String quizStatus,
      Long quizTotalMs) {}

  /** 后台首页和任务页使用的紧凑队列统计。 */
  record QueueStats(
      long queued,
      long running,
      long succeededSince,
      long failedSince,
      long averageFetchMs,
      long averageTranscribeMs,
      long averageSummarizeMs,
      long averageAudioDurationMs,
      long averageTotalMs) {}
}
