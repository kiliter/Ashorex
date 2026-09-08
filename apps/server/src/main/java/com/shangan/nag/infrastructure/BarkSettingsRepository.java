package com.shangan.nag.infrastructure;

import com.shangan.nag.domain.BarkSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 个人推送配置按用户主键读写，不提供跨用户修改入口。 */
@Repository
public class BarkSettingsRepository {
  private final JdbcClient jdbc;

  public BarkSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public BarkSettings find(String userId) {
    return jdbc.sql(
            "SELECT base_url, device_key, enabled FROM user_bark_settings WHERE user_id = :userId")
        .param("userId", userId)
        .query(
            (row, index) ->
                new BarkSettings(
                    row.getString("base_url"),
                    row.getString("device_key"),
                    row.getInt("enabled") == 1))
        .optional()
        .orElseGet(BarkSettings::defaults);
  }

  public void save(String userId, BarkSettings value) {
    jdbc.sql(
            """
      INSERT INTO user_bark_settings(user_id, base_url, device_key, enabled) VALUES (:userId, :url, :key, :enabled)
      ON CONFLICT(user_id) DO UPDATE SET base_url = excluded.base_url, device_key = excluded.device_key, enabled = excluded.enabled
      """)
        .param("userId", userId)
        .param("url", value.baseUrl())
        .param("key", value.deviceKey())
        .param("enabled", value.enabled() ? 1 : 0)
        .update();
  }
}
