package com.shangan.admin;

import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.util.List;

/** 管理后台外部服务配置表单；敏感字段只在 ADMIN 页面回填。 */
public record IntegrationSettingsForm(
    String embyBaseUrl,
    String embyApiKey,
    String embyUserId,
    String asrBaseUrl,
    String asrApiKey,
    String asrModel,
    String asrLanguage,
    Integer asrChunkDurationSeconds,
    Integer asrTimeoutSeconds,
    String llmBaseUrl,
    String llmApiKey,
    String llmModel,
    Integer llmContextLength,
    Integer llmMaxCompletionTokens,
    String llmReasoningEffort,
    Integer llmTimeoutSeconds,
    String openRouterApiKey,
    Boolean autoFillEnabled,
    Integer autoFillIntervalMinutes) {

  public IntegrationSettingsForm {
    embyBaseUrl = safe(embyBaseUrl);
    embyApiKey = safe(embyApiKey);
    embyUserId = safe(embyUserId);
    asrBaseUrl = safe(asrBaseUrl);
    asrApiKey = safe(asrApiKey);
    asrModel = safe(asrModel);
    asrLanguage = safe(asrLanguage);
    llmBaseUrl = safe(llmBaseUrl);
    llmApiKey = safe(llmApiKey);
    // OpenRouter 仅提供元数据，CPA 实际调用使用不含供应商命名空间的模型名。
    llmModel = withoutProviderPrefix(safe(llmModel));
    llmReasoningEffort = safe(llmReasoningEffort);
    openRouterApiKey = safe(openRouterApiKey);
    asrChunkDurationSeconds = asrChunkDurationSeconds == null ? 30 : asrChunkDurationSeconds;
    asrTimeoutSeconds = asrTimeoutSeconds == null ? 1800 : asrTimeoutSeconds;
    llmContextLength = llmContextLength == null ? 131072 : llmContextLength;
    llmMaxCompletionTokens = llmMaxCompletionTokens == null ? 8192 : llmMaxCompletionTokens;
    llmTimeoutSeconds = llmTimeoutSeconds == null ? 300 : llmTimeoutSeconds;
    autoFillEnabled = Boolean.TRUE.equals(autoFillEnabled);
    autoFillIntervalMinutes = autoFillIntervalMinutes == null ? 15 : autoFillIntervalMinutes;
  }

  /** 将当前不可变快照转换成可回填的表单。 */
  public static IntegrationSettingsForm from(RuntimeIntegrationSettings value) {
    return new IntegrationSettingsForm(
        value.emby().baseUrl(),
        value.emby().apiKey(),
        value.emby().userId(),
        value.asr().baseUrl(),
        value.asr().apiKey(),
        value.asr().model(),
        value.asr().language(),
        value.asr().chunkDurationSeconds(),
        value.asr().timeoutSeconds(),
        value.llm().baseUrl(),
        value.llm().apiKey(),
        value.llm().model(),
        value.llm().contextLength(),
        value.llm().maxCompletionTokens(),
        value.llm().reasoningEffort(),
        value.llm().timeoutSeconds(),
        value.openRouter().apiKey(),
        value.autoFill().enabled(),
        value.autoFill().intervalMinutes());
  }

  /** 构造待校验的完整配置快照。 */
  public RuntimeIntegrationSettings toSettings() {
    return toSettings(List.of());
  }

  /** 保存其他服务配置时显式保留已绑定媒体库，避免整表替换意外清空搜索范围。 */
  public RuntimeIntegrationSettings toSettings(
      List<RuntimeIntegrationSettings.EmbyLibrary> embyLibraries) {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(embyBaseUrl, embyApiKey, embyUserId),
        embyLibraries,
        new RuntimeIntegrationSettings.Asr(
            asrBaseUrl,
            asrApiKey,
            asrModel,
            asrLanguage,
            asrChunkDurationSeconds,
            asrTimeoutSeconds),
        new RuntimeIntegrationSettings.Llm(
            llmBaseUrl,
            llmApiKey,
            llmModel,
            llmContextLength,
            llmMaxCompletionTokens,
            llmTimeoutSeconds,
            llmReasoningEffort),
        new RuntimeIntegrationSettings.OpenRouter(openRouterApiKey),
        new RuntimeIntegrationSettings.AutoFill(
            Boolean.TRUE.equals(autoFillEnabled), autoFillIntervalMinutes),
        0);
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static String withoutProviderPrefix(String model) {
    int separator = model.indexOf('/');
    return separator >= 0 && separator + 1 < model.length()
        ? model.substring(separator + 1)
        : model;
  }
}
