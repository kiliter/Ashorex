package com.shangan.admin;

import com.shangan.common.integration.IntegrationSettingsValidationException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.util.LinkedHashMap;
import java.util.Map;

/** 管理后台服务配置表单；数值先按文本接收，以便返回明确的中文字段错误。 */
public record IntegrationSettingsForm(
    String embyBaseUrl,
    String embyApiKey,
    String embyUserId,
    String llmBaseUrl,
    String llmApiKey,
    String llmModel,
    String llmMaxContextTokens,
    String llmTemperature,
    String llmTimeoutSeconds,
    String asrBaseUrl,
    String asrApiKey,
    String asrModel,
    String asrTimeoutSeconds,
    String mcpUrl,
    String mcpBearerToken,
    String mcpAllowedTools,
    String mcpTimeoutSeconds) {

  public IntegrationSettingsForm {
    embyBaseUrl = safe(embyBaseUrl);
    embyApiKey = safe(embyApiKey);
    embyUserId = safe(embyUserId);
    llmBaseUrl = safe(llmBaseUrl);
    llmApiKey = safe(llmApiKey);
    llmModel = safe(llmModel);
    llmMaxContextTokens = safe(llmMaxContextTokens);
    llmTemperature = safe(llmTemperature);
    llmTimeoutSeconds = safe(llmTimeoutSeconds);
    asrBaseUrl = safe(asrBaseUrl);
    asrApiKey = safe(asrApiKey);
    asrModel = safe(asrModel);
    asrTimeoutSeconds = safe(asrTimeoutSeconds);
    mcpUrl = safe(mcpUrl);
    mcpBearerToken = safe(mcpBearerToken);
    mcpAllowedTools = safe(mcpAllowedTools);
    mcpTimeoutSeconds = safe(mcpTimeoutSeconds);
  }

  /** 将当前不可变快照转换成可回填的表单。 */
  public static IntegrationSettingsForm from(RuntimeIntegrationSettings value) {
    return new IntegrationSettingsForm(
        value.emby().baseUrl(),
        value.emby().apiKey(),
        value.emby().userId(),
        value.llm().baseUrl(),
        value.llm().apiKey(),
        value.llm().model(),
        Integer.toString(value.llm().maxContextTokens()),
        Double.toString(value.llm().temperature()),
        Integer.toString(value.llm().timeoutSeconds()),
        value.asr().baseUrl(),
        value.asr().apiKey(),
        value.asr().model(),
        Integer.toString(value.asr().timeoutSeconds()),
        value.mcp().url(),
        value.mcp().bearerToken(),
        value.mcp().allowedTools(),
        Integer.toString(value.mcp().timeoutSeconds()));
  }

  /** 解析数值字段并构造待校验配置；解析失败时一次返回全部字段错误。 */
  public RuntimeIntegrationSettings toSettings() {
    Map<String, String> errors = new LinkedHashMap<>();
    int contextTokens =
        integer("llmMaxContextTokens", "最大上下文 Token 数", llmMaxContextTokens, errors);
    double temperature = decimal("llmTemperature", "Temperature", llmTemperature, errors);
    int llmTimeout = integer("llmTimeoutSeconds", "LLM 超时", llmTimeoutSeconds, errors);
    int asrTimeout = integer("asrTimeoutSeconds", "ASR 超时", asrTimeoutSeconds, errors);
    int mcpTimeout = integer("mcpTimeoutSeconds", "MCP 超时", mcpTimeoutSeconds, errors);
    if (!errors.isEmpty()) throw new IntegrationSettingsValidationException(errors);
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(embyBaseUrl, embyApiKey, embyUserId),
        new RuntimeIntegrationSettings.Llm(
            llmBaseUrl, llmApiKey, llmModel, contextTokens, temperature, llmTimeout),
        new RuntimeIntegrationSettings.Asr(asrBaseUrl, asrApiKey, asrModel, asrTimeout),
        new RuntimeIntegrationSettings.Mcp(mcpUrl, mcpBearerToken, mcpAllowedTools, mcpTimeout),
        0);
  }

  private static int integer(String field, String label, String value, Map<String, String> errors) {
    try {
      return Integer.parseInt(value.trim());
    } catch (RuntimeException exception) {
      errors.put(field, label + " 必须是整数");
      return 0;
    }
  }

  private static double decimal(
      String field, String label, String value, Map<String, String> errors) {
    try {
      return Double.parseDouble(value.trim());
    } catch (RuntimeException exception) {
      errors.put(field, label + " 必须是数字");
      return 0;
    }
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
