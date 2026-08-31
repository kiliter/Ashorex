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
