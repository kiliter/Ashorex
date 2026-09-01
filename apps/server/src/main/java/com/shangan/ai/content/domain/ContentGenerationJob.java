package com.shangan.ai.content.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 持久化内容任务快照；状态转换规则由枚举集中维护。 */
public record ContentGenerationJob(
    String id,
    String courseId,
    String mediaItemId,
    Type type,
    Status status,
    int requestedQuestionCount,
    boolean overwriteExisting,
    Instant queuedAt,
    Instant startedAt,
    Instant finishedAt,
    Long audioDurationMs,
    Long fetchMs,
    Long transcribeMs,
    Long summarizeMs,
    Long quizGenerateMs,
    Long totalMs,
    String asrModel,
    String llmModel,
    Integer llmContextLength,
    Integer llmMaxCompletionTokens,
    Integer promptTokens,
    Integer completionTokens,
    int attempt,
    String errorCode,
    String errorMessage,
    String createdBy) {

  public enum Type {
    TRANSCRIBE("转写全文"),
    SUMMARIZE("生成摘要"),
    GENERATE_QUIZ("生成题目");

    private final String label;

    Type(String label) {
      this.label = label;
    }

    /** 返回管理后台使用的中文任务类型名称。 */
    public String label() {
      return label;
    }
  }

  public enum Status {
    QUEUED("等待执行"),
    FETCHING_AUDIO("获取音频"),
    TRANSCRIBING("正在转写"),
    SUMMARIZING("正在生成摘要"),
    GENERATING_QUIZ("正在生成题目"),
    READY("已完成"),
    READY_FOR_REVIEW("待审核"),
    FAILED("失败");

    private final String label;

    private static final Map<Type, Map<Status, Set<Status>>> TRANSITIONS =
        Map.of(
            Type.TRANSCRIBE,
                Map.of(
                    QUEUED, EnumSet.of(FETCHING_AUDIO, FAILED),
                    FETCHING_AUDIO, EnumSet.of(TRANSCRIBING, FAILED),
                    TRANSCRIBING, EnumSet.of(READY, FAILED)),
            Type.SUMMARIZE,
                Map.of(
                    QUEUED, EnumSet.of(SUMMARIZING, FAILED),
                    SUMMARIZING, EnumSet.of(READY, FAILED)),
            Type.GENERATE_QUIZ,
                Map.of(
                    QUEUED, EnumSet.of(GENERATING_QUIZ, FAILED),
                    GENERATING_QUIZ, EnumSet.of(READY_FOR_REVIEW, FAILED)));

    Status(String label) {
      this.label = label;
    }

    /** 返回管理后台使用的中文任务状态名称。 */
    public String label() {
      return label;
    }

    public boolean canTransitionTo(Type type, Status target) {
      return TRANSITIONS.getOrDefault(type, Map.of()).getOrDefault(this, Set.of()).contains(target);
    }

    /** 非法状态变化必须显式失败，避免 Worker 把任务跳过中间阶段。 */
    public void requireTransition(Type type, Status target) {
      if (!canTransitionTo(type, target)) {
        throw new IllegalStateException("内容任务非法状态转换: " + type + " " + this + " -> " + target);
      }
    }

    public boolean terminal() {
      return this == READY || this == READY_FOR_REVIEW || this == FAILED;
    }
  }

  /** 不记录密钥、正文和 Prompt 的任务阶段日志。 */
  public record Log(
      String id, String jobId, Instant occurredAt, String level, String stage, String message) {}
}
