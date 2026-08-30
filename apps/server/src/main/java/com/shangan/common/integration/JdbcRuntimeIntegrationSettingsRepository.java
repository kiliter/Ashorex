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
              llm_base_url, llm_api_key, llm_model, llm_max_context_tokens,
              llm_temperature, llm_timeout_seconds,
              asr_base_url, asr_api_key, asr_model, asr_timeout_seconds,
              mcp_url, mcp_bearer_token, mcp_allowed_tools, mcp_timeout_seconds, updated_at
            ) values (
              'default', :embyBaseUrl, :embyApiKey, :embyUserId,
              :llmBaseUrl, :llmApiKey, :llmModel, :llmMaxContextTokens,
              :llmTemperature, :llmTimeoutSeconds,
              :asrBaseUrl, :asrApiKey, :asrModel, :asrTimeoutSeconds,
              :mcpUrl, :mcpBearerToken, :mcpAllowedTools, :mcpTimeoutSeconds, :updatedAt
            )
            on conflict(id) do update set
              emby_base_url = excluded.emby_base_url,
              emby_api_key = excluded.emby_api_key,
              emby_user_id = excluded.emby_user_id,
              llm_base_url = excluded.llm_base_url,
              llm_api_key = excluded.llm_api_key,
              llm_model = excluded.llm_model,
              llm_max_context_tokens = excluded.llm_max_context_tokens,
              llm_temperature = excluded.llm_temperature,
              llm_timeout_seconds = excluded.llm_timeout_seconds,
              asr_base_url = excluded.asr_base_url,
              asr_api_key = excluded.asr_api_key,
              asr_model = excluded.asr_model,
              asr_timeout_seconds = excluded.asr_timeout_seconds,
              mcp_url = excluded.mcp_url,
              mcp_bearer_token = excluded.mcp_bearer_token,
              mcp_allowed_tools = excluded.mcp_allowed_tools,
              mcp_timeout_seconds = excluded.mcp_timeout_seconds,
              updated_at = excluded.updated_at
            """)
        .param("embyBaseUrl", value.emby().baseUrl())
        .param("embyApiKey", value.emby().apiKey())
        .param("embyUserId", value.emby().userId())
        .param("llmBaseUrl", value.llm().baseUrl())
        .param("llmApiKey", value.llm().apiKey())
        .param("llmModel", value.llm().model())
        .param("llmMaxContextTokens", value.llm().maxContextTokens())
        .param("llmTemperature", value.llm().temperature())
        .param("llmTimeoutSeconds", value.llm().timeoutSeconds())
        .param("asrBaseUrl", value.asr().baseUrl())
        .param("asrApiKey", value.asr().apiKey())
        .param("asrModel", value.asr().model())
        .param("asrTimeoutSeconds", value.asr().timeoutSeconds())
        .param("mcpUrl", value.mcp().url())
        .param("mcpBearerToken", value.mcp().bearerToken())
        .param("mcpAllowedTools", value.mcp().allowedTools())
        .param("mcpTimeoutSeconds", value.mcp().timeoutSeconds())
        .param("updatedAt", value.updatedAt())
        .update();
  }

  private RuntimeIntegrationSettings map(ResultSet row, int rowNumber) throws SQLException {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(
            row.getString("emby_base_url"),
            row.getString("emby_api_key"),
            row.getString("emby_user_id")),
        new RuntimeIntegrationSettings.Llm(
            row.getString("llm_base_url"),
            row.getString("llm_api_key"),
            row.getString("llm_model"),
            row.getInt("llm_max_context_tokens"),
            row.getDouble("llm_temperature"),
            row.getInt("llm_timeout_seconds")),
        new RuntimeIntegrationSettings.Asr(
            row.getString("asr_base_url"),
            row.getString("asr_api_key"),
            row.getString("asr_model"),
            row.getInt("asr_timeout_seconds")),
        new RuntimeIntegrationSettings.Mcp(
            row.getString("mcp_url"),
            row.getString("mcp_bearer_token"),
            row.getString("mcp_allowed_tools"),
            row.getInt("mcp_timeout_seconds")),
        row.getLong("updated_at"));
  }
}
