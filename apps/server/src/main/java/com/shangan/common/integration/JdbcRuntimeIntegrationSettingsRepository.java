package com.shangan.common.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 运行时集成配置的持久化实现；配置固定单行，媒体库绑定以 JSON 数组存储。 */
@Repository
public class JdbcRuntimeIntegrationSettingsRepository
    implements RuntimeIntegrationSettingsRepository {

  private static final String SINGLETON_ID = "runtime";

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public JdbcRuntimeIntegrationSettingsRepository(
      JdbcClient jdbcClient, ObjectMapper objectMapper, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public Optional<RuntimeIntegrationSettings> find() {
    return jdbcClient
        .sql(
            """
            SELECT emby_base_url, emby_api_key, emby_user_id, emby_timeout_seconds,
                   emby_libraries_json,
                   serverchan_send_key, serverchan_timeout_seconds,
                   serverchan_nag_enabled, serverchan_daily_digest_enabled,
                   feature_document_resources, feature_max_document_size_mb,
                   bark_base_url, bark_device_key, bark_enabled, bark_timeout_seconds, updated_at
              FROM runtime_settings
             WHERE id = :id
            """)
        .param("id", SINGLETON_ID)
        .query(
            (row, rowNumber) ->
                new RuntimeIntegrationSettings(
                    new RuntimeIntegrationSettings.Emby(
                        row.getString("emby_base_url"),
                        row.getString("emby_api_key"),
                        row.getString("emby_user_id"),
                        row.getInt("emby_timeout_seconds")),
                    parseLibraries(row.getString("emby_libraries_json")),
                    new RuntimeIntegrationSettings.ServerChan(
                        row.getString("serverchan_send_key"),
                        row.getInt("serverchan_timeout_seconds"),
                        row.getInt("serverchan_nag_enabled") == 1,
                        row.getInt("serverchan_daily_digest_enabled") == 1),
                    new RuntimeIntegrationSettings.Features(
                        row.getInt("feature_document_resources") == 1,
                        row.getInt("feature_max_document_size_mb")),
                    row.getLong("updated_at"),
                    new RuntimeIntegrationSettings.Bark(
                        row.getString("bark_base_url"),
                        row.getString("bark_device_key"),
                        row.getInt("bark_enabled") == 1,
                        row.getInt("bark_timeout_seconds"))))
        .optional();
  }

  @Override
  public void replace(RuntimeIntegrationSettings value) {
    jdbcClient
        .sql(
            """
            INSERT INTO runtime_settings (
                id, emby_base_url, emby_api_key, emby_user_id, emby_timeout_seconds,
                emby_libraries_json,
                serverchan_send_key, serverchan_timeout_seconds,
                serverchan_nag_enabled, serverchan_daily_digest_enabled,
                feature_document_resources, feature_max_document_size_mb,
                bark_base_url, bark_device_key, bark_enabled, bark_timeout_seconds, updated_at
            ) VALUES (
                :id, :embyBaseUrl, :embyApiKey, :embyUserId, :embyTimeoutSeconds,
                :embyLibrariesJson,
                :serverChanSendKey, :serverChanTimeoutSeconds,
                :serverChanNagEnabled, :serverChanDailyDigestEnabled,
                :featureDocumentResources, :featureMaxDocumentSizeMb,
                :barkBaseUrl, :barkDeviceKey, :barkEnabled, :barkTimeoutSeconds, :updatedAt
            )
            ON CONFLICT(id) DO UPDATE SET
                emby_base_url = excluded.emby_base_url,
                emby_api_key = excluded.emby_api_key,
                emby_user_id = excluded.emby_user_id,
                emby_timeout_seconds = excluded.emby_timeout_seconds,
                emby_libraries_json = excluded.emby_libraries_json,
                serverchan_send_key = excluded.serverchan_send_key,
                serverchan_timeout_seconds = excluded.serverchan_timeout_seconds,
                serverchan_nag_enabled = excluded.serverchan_nag_enabled,
                serverchan_daily_digest_enabled = excluded.serverchan_daily_digest_enabled,
                feature_document_resources = excluded.feature_document_resources,
                feature_max_document_size_mb = excluded.feature_max_document_size_mb,
                bark_base_url = excluded.bark_base_url,
                bark_device_key = excluded.bark_device_key,
                bark_enabled = excluded.bark_enabled,
                bark_timeout_seconds = excluded.bark_timeout_seconds,
                updated_at = excluded.updated_at
            """)
        .param("id", SINGLETON_ID)
        .param("embyBaseUrl", value.emby().baseUrl())
        .param("embyApiKey", value.emby().apiKey())
        .param("embyUserId", value.emby().userId())
        .param("embyTimeoutSeconds", value.emby().timeoutSeconds())
        .param("embyLibrariesJson", librariesJson(value.embyLibraries()))
        .param("serverChanSendKey", value.serverChan().sendKey())
        .param("serverChanTimeoutSeconds", value.serverChan().timeoutSeconds())
        .param("serverChanNagEnabled", value.serverChan().nagEnabled() ? 1 : 0)
        .param("serverChanDailyDigestEnabled", value.serverChan().dailyDigestEnabled() ? 1 : 0)
        .param("featureDocumentResources", value.features().documentResources() ? 1 : 0)
        .param("featureMaxDocumentSizeMb", value.features().maxDocumentSizeMb())
        .param("barkBaseUrl", value.bark().baseUrl())
        .param("barkDeviceKey", value.bark().deviceKey())
        .param("barkEnabled", value.bark().enabled() ? 1 : 0)
        .param("barkTimeoutSeconds", value.bark().timeoutSeconds())
        .param("updatedAt", clock.millis())
        .update();
  }

  /** 媒体库绑定序列化为紧凑 JSON 数组，避免为少量固定数据单独建表。 */
  private String librariesJson(List<RuntimeIntegrationSettings.EmbyLibrary> libraries) {
    ArrayNode array = objectMapper.createArrayNode();
    for (RuntimeIntegrationSettings.EmbyLibrary library : libraries) {
      ObjectNode node = array.addObject();
      node.put("id", library.id());
      node.put("name", library.name());
      node.put("contentType", library.contentType().name());
    }
    return array.toString();
  }

  /** 解析失败时返回空列表而不是抛出，避免一处脏数据让整个后台不可用。 */
  private List<RuntimeIntegrationSettings.EmbyLibrary> parseLibraries(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<RuntimeIntegrationSettings.EmbyLibrary> result = new ArrayList<>();
      for (var node : objectMapper.readTree(json)) {
        String contentType = node.path("contentType").asText("MIXED");
        result.add(
            new RuntimeIntegrationSettings.EmbyLibrary(
                node.path("id").asText(""),
                node.path("name").asText(""),
                parseContentType(contentType)));
      }
      return List.copyOf(result);
    } catch (JsonProcessingException exception) {
      return List.of();
    }
  }

  private RuntimeIntegrationSettings.EmbyLibraryType parseContentType(String value) {
    try {
      return RuntimeIntegrationSettings.EmbyLibraryType.valueOf(value);
    } catch (IllegalArgumentException exception) {
      return RuntimeIntegrationSettings.EmbyLibraryType.MIXED;
    }
  }
}
