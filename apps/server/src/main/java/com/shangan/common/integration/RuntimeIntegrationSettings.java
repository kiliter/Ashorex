package com.shangan.common.integration;

/** Emby、LLM、ASR 和 MCP 的不可变运行时配置快照。 */
public record RuntimeIntegrationSettings(Emby emby, Llm llm, Asr asr, Mcp mcp, long updatedAt) {

  /** Emby 固定源站配置；用户 ID 可为空，但地址和密钥必须同时存在才视为已配置。 */
  public record Emby(String baseUrl, String apiKey, String userId) {
    public boolean configured() {
      return present(baseUrl) && present(apiKey);
    }
  }

  /** OpenAI-compatible LLM 配置，超时单位为秒。 */
  public record Llm(
      String baseUrl,
      String apiKey,
      String model,
      int maxContextTokens,
      double temperature,
      int timeoutSeconds) {
    public boolean configured() {
      return present(baseUrl) && present(apiKey) && present(model);
    }
  }

  /** OpenAI-compatible ASR 配置，超时单位为秒。 */
  public record Asr(String baseUrl, String apiKey, String model, int timeoutSeconds) {
    public boolean configured() {
      return present(baseUrl) && present(apiKey) && present(model);
    }
  }

  /** Streamable HTTP MCP 配置；Bearer Token 可为空。 */
  public record Mcp(String url, String bearerToken, String allowedTools, int timeoutSeconds) {
    public boolean configured() {
      return present(url);
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
