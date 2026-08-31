package com.shangan.media.emby;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用固定配置访问 Emby API，任何返回对象都不会携带服务端密钥。 */
@Component
public class EmbyClient implements EmbyGateway {

  private static final long TICKS_PER_MILLISECOND = 10_000L;

  private final EmbyProperties properties;
  private final ObjectMapper objectMapper;

  public EmbyClient(EmbyProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /** 查询固定父节点下的视频，并将 Emby ticks 转换成业务毫秒。 */
  @Override
  public List<EmbyDtos.MediaItem> listChildren(String parentItemId) {
    RuntimeIntegrationSettings.Emby configuration = properties.current();
    if (!configuration.configured()) {
      throw unavailable();
    }
    try {
      String body =
          RestClient.builder()
              .baseUrl(configuration.baseUrl())
              .defaultHeader("X-Emby-Token", configuration.apiKey())
              // 部分 Emby 前置代理返回不兼容的 deflate 数据，元数据请求直接禁用压缩。
              .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
              .build()
              .get()
              .uri(
                  builder ->
                      builder
                          .path("/Items")
                          .queryParam("ParentId", parentItemId)
                          .queryParam("Recursive", true)
                          .queryParam("IncludeItemTypes", "Episode,Video")
                          .queryParam("Fields", "RunTimeTicks,IndexNumber")
                          .build())
              .retrieve()
              .body(String.class);
      JsonNode items = objectMapper.readTree(body).path("Items");
      List<EmbyDtos.MediaItem> result = new ArrayList<>();
      for (JsonNode item : items) {
        if (!item.path("Id").isMissingNode() && !item.path("Name").isMissingNode()) {
          result.add(
              new EmbyDtos.MediaItem(
                  item.path("Id").asText(),
                  item.path("Name").asText(),
                  Math.max(0, item.path("RunTimeTicks").asLong() / TICKS_PER_MILLISECOND),
                  item.path("IndexNumber").asInt(result.size() + 1)));
        }
      }
      return List.copyOf(result);
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private BusinessException unavailable() {
    return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMBY_UNAVAILABLE", "媒体服务暂时不可用");
  }
}
