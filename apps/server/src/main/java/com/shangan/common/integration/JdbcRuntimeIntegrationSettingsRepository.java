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
              id, emby_base_url, emby_api_key, emby_user_id, updated_at
            ) values (
              'default', :embyBaseUrl, :embyApiKey, :embyUserId, :updatedAt
            )
            on conflict(id) do update set
              emby_base_url = excluded.emby_base_url,
              emby_api_key = excluded.emby_api_key,
              emby_user_id = excluded.emby_user_id,
              updated_at = excluded.updated_at
            """)
        .param("embyBaseUrl", value.emby().baseUrl())
        .param("embyApiKey", value.emby().apiKey())
        .param("embyUserId", value.emby().userId())
        .param("updatedAt", value.updatedAt())
        .update();
  }

  private RuntimeIntegrationSettings map(ResultSet row, int rowNumber) throws SQLException {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(
            row.getString("emby_base_url"),
            row.getString("emby_api_key"),
            row.getString("emby_user_id")),
        row.getLong("updated_at"));
  }
}
