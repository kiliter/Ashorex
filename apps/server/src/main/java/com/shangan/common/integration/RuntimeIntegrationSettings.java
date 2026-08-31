package com.shangan.common.integration;

/** Emby、ASR、LLM 和模型目录的不可变运行时配置快照。 */
public record RuntimeIntegrationSettings(
    Emby emby, Asr asr, Llm llm, OpenRouter openRouter, AutoFill autoFill, long updatedAt) {

  public static final String DEFAULT_ASR_MODEL = "mlx-community/Qwen3-ASR-1.7B-8bit";

  /** 兼容只关心 Emby 的既有测试和调用方，其余能力保持未配置且定时补全关闭。 */
  public RuntimeIntegrationSettings(Emby emby, long updatedAt) {
    this(emby, Asr.defaults(), Llm.defaults(), new OpenRouter(""), AutoFill.defaults(), updatedAt);
  }

  /** Emby 固定源站配置；用户 ID 可为空，但地址和密钥必须同时存在才视为已配置。 */
  public record Emby(String baseUrl, String apiKey, String userId) {
    public boolean configured() {
      return present(baseUrl) && present(apiKey);
    }
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
      int timeoutSeconds) {
    public boolean configured() {
      return present(baseUrl) && present(model) && contextLength >= 4096 && maxCompletionTokens > 0;
    }

    public static Llm defaults() {
      return new Llm("", "", "", 131072, 8192, 300);
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
