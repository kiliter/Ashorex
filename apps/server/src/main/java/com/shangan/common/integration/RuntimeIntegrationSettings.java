package com.shangan.common.integration;

import java.util.List;

/** Emby、ASR、LLM 和模型目录的不可变运行时配置快照。 */
public record RuntimeIntegrationSettings(
    Emby emby,
    List<EmbyLibrary> embyLibraries,
    Asr asr,
    Llm llm,
    OpenRouter openRouter,
    AutoFill autoFill,
    long updatedAt) {

  public static final String DEFAULT_ASR_MODEL = "mlx-community/Qwen3-ASR-1.7B-8bit";

  public RuntimeIntegrationSettings {
    embyLibraries = embyLibraries == null ? List.of() : List.copyOf(embyLibraries);
  }

  /** 兼容 Task 25 前的完整构造方式；旧调用方默认没有媒体库绑定。 */
  public RuntimeIntegrationSettings(
      Emby emby, Asr asr, Llm llm, OpenRouter openRouter, AutoFill autoFill, long updatedAt) {
    this(emby, List.of(), asr, llm, openRouter, autoFill, updatedAt);
  }

  /** 兼容只关心 Emby 的既有测试和调用方，其余能力保持未配置且定时补全关闭。 */
  public RuntimeIntegrationSettings(Emby emby, long updatedAt) {
    this(
        emby,
        List.of(),
        Asr.defaults(),
        Llm.defaults(),
        new OpenRouter(""),
        AutoFill.defaults(),
        updatedAt);
  }

  /** Emby 固定源站配置；用户 ID 可为空，但地址和密钥必须同时存在才视为已配置。 */
  public record Emby(String baseUrl, String apiKey, String userId) {
    public boolean configured() {
      return present(baseUrl) && present(apiKey);
    }
  }

  /** 管理员允许课程来源搜索使用的一个顶层媒体库。 */
  public record EmbyLibrary(String id, String name, EmbyLibraryType contentType) {}

  /** 一个媒体库允许提供剧集、电影或两类内容。 */
  public enum EmbyLibraryType {
    SERIES,
    MOVIE,
    MIXED
  }

  /** OpenAI-compatible ASR 配置；本地服务允许不填写 API Key。 */
  public record Asr(
      String baseUrl,
      String apiKey,
      String model,
      String language,
      int chunkDurationSeconds,
      int timeoutSeconds) {
    public boolean configured() {
      return present(baseUrl) && present(model);
    }

    public static Asr defaults() {
      return new Asr("", "", DEFAULT_ASR_MODEL, "Chinese", 30, 1800);
    }
  }

  /** OpenAI-compatible LLM 配置；上下文参数用于长文本预算而不是猜测模型能力。 */
  public record Llm(
      String baseUrl,
      String apiKey,
      String model,
      int contextLength,
      int maxCompletionTokens,
      int timeoutSeconds,
      String reasoningEffort) {
    /** 兼容既有调用方；未显式配置时不向上游发送 reasoning_effort。 */
    public Llm(
        String baseUrl,
        String apiKey,
        String model,
        int contextLength,
        int maxCompletionTokens,
        int timeoutSeconds) {
      this(baseUrl, apiKey, model, contextLength, maxCompletionTokens, timeoutSeconds, "");
    }

    public boolean configured() {
      return present(baseUrl) && present(model) && contextLength >= 4096 && maxCompletionTokens > 0;
    }

    public static Llm defaults() {
      return new Llm("", "", "", 131072, 8192, 300, "");
    }
  }

  /** OpenRouter 仅用于读取公开模型目录，不决定实际推理上游。 */
  public record OpenRouter(String apiKey) {}

  /** 缺失内容定时补全配置，默认关闭且不会生成题目。 */
  public record AutoFill(boolean enabled, int intervalMinutes) {
    public static AutoFill defaults() {
      return new AutoFill(false, 15);
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
