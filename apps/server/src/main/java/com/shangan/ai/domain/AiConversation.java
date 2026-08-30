package com.shangan.ai.domain;

import java.time.Instant;

/** 用户拥有的 AI 会话；scope 决定是否允许装配某个视频的只读上下文。 */
public record AiConversation(
    String id,
    String userId,
    String scope,
    String mediaItemId,
    String title,
    String historySummary,
    Instant createdAt,
    Instant updatedAt) {

  public AiConversation {
    if (!scope.equals("GENERAL") && !scope.equals("VIDEO")) {
      throw new IllegalArgumentException("AI 会话范围无效");
    }
    if (scope.equals("VIDEO") && (mediaItemId == null || mediaItemId.isBlank())) {
      throw new IllegalArgumentException("视频会话必须关联课时");
    }
  }

  /** 持久化消息视图；FAILED 助手消息保留已输出内容，供客户端展示和重试。 */
  public record Message(
      String id,
      String conversationId,
      String role,
      String content,
      String status,
      String citationsJson,
      String modelName,
      int inputTokens,
      int outputTokens,
      Instant createdAt,
      Instant updatedAt) {}

  /** 统一承载联网来源和视频时间戳，客户端不需要解析模型正文。 */
  public record Citation(String type, String title, String url, Long positionMs) {}
}
