package com.shangan.common.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用 JdbcClient 读取和整体替换固定的运行时配置行。 */
@Repository
public class JdbcRuntimeIntegrationSettingsRepository
    implements RuntimeIntegrationSettingsRepository {

  private final JdbcClient jdbc;

  public JdbcRuntimeIntegrationSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public java.util.Optional<RuntimeIntegrationSettings> find() {
    return jdbc.sql("select * from runtime_integration_settings where id = 'default'")
        .query(this::map)
        .optional();
  }

  @Override
  public void replace(RuntimeIntegrationSettings value) {
    jdbc.sql(
            """
            insert into runtime_integration_settings (
              id, emby_base_url, emby_api_key, emby_user_id,
              asr_base_url, asr_api_key, asr_model, asr_language,
              asr_chunk_duration_seconds, asr_timeout_seconds,
              llm_base_url, llm_api_key, llm_model, llm_context_length,
              llm_max_completion_tokens, llm_timeout_seconds,
              openrouter_api_key, content_auto_fill_enabled,
              content_auto_fill_interval_minutes, updated_at
            ) values (
              'default', :embyBaseUrl, :embyApiKey, :embyUserId,
              :asrBaseUrl, :asrApiKey, :asrModel, :asrLanguage,
              :asrChunkDurationSeconds, :asrTimeoutSeconds,
              :llmBaseUrl, :llmApiKey, :llmModel, :llmContextLength,
              :llmMaxCompletionTokens, :llmTimeoutSeconds,
              :openRouterApiKey, :autoFillEnabled, :autoFillIntervalMinutes, :updatedAt
            )
            on conflict(id) do update set
              emby_base_url = excluded.emby_base_url,
              emby_api_key = excluded.emby_api_key,
              emby_user_id = excluded.emby_user_id,
              asr_base_url = excluded.asr_base_url,
              asr_api_key = excluded.asr_api_key,
              asr_model = excluded.asr_model,
              asr_language = excluded.asr_language,
              asr_chunk_duration_seconds = excluded.asr_chunk_duration_seconds,
              asr_timeout_seconds = excluded.asr_timeout_seconds,
              llm_base_url = excluded.llm_base_url,
              llm_api_key = excluded.llm_api_key,
              llm_model = excluded.llm_model,
              llm_context_length = excluded.llm_context_length,
              llm_max_completion_tokens = excluded.llm_max_completion_tokens,
              llm_timeout_seconds = excluded.llm_timeout_seconds,
              openrouter_api_key = excluded.openrouter_api_key,
              content_auto_fill_enabled = excluded.content_auto_fill_enabled,
              content_auto_fill_interval_minutes = excluded.content_auto_fill_interval_minutes,
              updated_at = excluded.updated_at
            """)
        .param("embyBaseUrl", value.emby().baseUrl())
        .param("embyApiKey", value.emby().apiKey())
        .param("embyUserId", value.emby().userId())
        .param("asrBaseUrl", value.asr().baseUrl())
        .param("asrApiKey", value.asr().apiKey())
        .param("asrModel", value.asr().model())
        .param("asrLanguage", value.asr().language())
        .param("asrChunkDurationSeconds", value.asr().chunkDurationSeconds())
        .param("asrTimeoutSeconds", value.asr().timeoutSeconds())
        .param("llmBaseUrl", value.llm().baseUrl())
        .param("llmApiKey", value.llm().apiKey())
        .param("llmModel", value.llm().model())
        .param("llmContextLength", value.llm().contextLength())
        .param("llmMaxCompletionTokens", value.llm().maxCompletionTokens())
        .param("llmTimeoutSeconds", value.llm().timeoutSeconds())
        .param("openRouterApiKey", value.openRouter().apiKey())
        .param("autoFillEnabled", value.autoFill().enabled() ? 1 : 0)
        .param("autoFillIntervalMinutes", value.autoFill().intervalMinutes())
        .param("updatedAt", value.updatedAt())
        .update();
  }

  private RuntimeIntegrationSettings map(ResultSet row, int rowNumber) throws SQLException {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(
            row.getString("emby_base_url"),
            row.getString("emby_api_key"),
            row.getString("emby_user_id")),
        new RuntimeIntegrationSettings.Asr(
            row.getString("asr_base_url"),
            row.getString("asr_api_key"),
            row.getString("asr_model"),
            row.getString("asr_language"),
            row.getInt("asr_chunk_duration_seconds"),
            row.getInt("asr_timeout_seconds")),
        new RuntimeIntegrationSettings.Llm(
            row.getString("llm_base_url"),
            row.getString("llm_api_key"),
            row.getString("llm_model"),
            row.getInt("llm_context_length"),
            row.getInt("llm_max_completion_tokens"),
            row.getInt("llm_timeout_seconds")),
        new RuntimeIntegrationSettings.OpenRouter(row.getString("openrouter_api_key")),
        new RuntimeIntegrationSettings.AutoFill(
            row.getInt("content_auto_fill_enabled") == 1,
            row.getInt("content_auto_fill_interval_minutes")),
        row.getLong("updated_at"));
  }
}
