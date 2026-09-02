package com.shangan.media.emby;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用固定配置访问 Emby API，分页结果和返回 DTO 均不携带密钥或原始媒体路径。 */
@Component
public class EmbyClient implements EmbyGateway {

  private static final long TICKS_PER_MILLISECOND = 10_000L;
  private static final int DEFAULT_PAGE_SIZE = 500;
  private static final Set<String> SUPPORTED_LIBRARY_TYPES =
      Set.of("", "movies", "tvshows", "mixed", "homevideos", "musicvideos", "folders");

  private final EmbyProperties properties;
  private final ObjectMapper objectMapper;
  private final int pageSize;
  private final JdkClientHttpRequestFactory requestFactory;

  /** Spring 生产构造器；测试专用分页构造器不参与依赖注入。 */
  @Autowired
  public EmbyClient(EmbyProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, DEFAULT_PAGE_SIZE);
  }

  /** 允许协议测试使用极小分页；生产构造器固定为 500 条一页。 */
  EmbyClient(EmbyProperties properties, ObjectMapper objectMapper, int pageSize) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.pageSize = pageSize;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    this.requestFactory = new JdkClientHttpRequestFactory(httpClient);
    this.requestFactory.setReadTimeout(Duration.ofSeconds(30));
  }

  /** 查询配置用户可见的视频媒体库，过滤音乐、图片、播放列表等非视频视图。 */
  @Override
  public List<EmbyDtos.MediaLibrary> listMediaLibraries() {
    RuntimeIntegrationSettings.Emby configuration = requiredConfiguration();
    try {
      String body =
          client(configuration)
              .get()
              .uri("/Users/{userId}/Views", configuration.userId())
              .retrieve()
              .body(String.class);
      List<EmbyDtos.MediaLibrary> result = new ArrayList<>();
      for (JsonNode item : objectMapper.readTree(body).path("Items")) {
        String collectionType = item.path("CollectionType").asText("");
        if (validIdentity(item)
            && SUPPORTED_LIBRARY_TYPES.contains(collectionType.toLowerCase(Locale.ROOT))) {
          result.add(
              new EmbyDtos.MediaLibrary(
                  item.path("Id").asText(), item.path("Name").asText(), collectionType));
        }
      }
      return List.copyOf(result);
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  /** 验证父节点后，在配置用户作用域内分页递归读取全部电影、剧集和普通视频。 */
  @Override
  public List<EmbyDtos.MediaItem> listChildren(String parentItemId) {
    RuntimeIntegrationSettings.Emby configuration = requiredConfiguration();
    validateParent(configuration, parentItemId);
    try {
      Map<String, EmbyDtos.MediaItem> uniqueItems = new LinkedHashMap<>();
      int startIndex = 0;
      int totalRecordCount;
      do {
        String body = requestPage(configuration, parentItemId, startIndex);
        JsonNode response = objectMapper.readTree(body);
        JsonNode items = response.path("Items");
        totalRecordCount = response.path("TotalRecordCount").asInt(items.size());
        int pageItemCount = items.size();
        if (pageItemCount == 0 && startIndex < totalRecordCount) {
          throw unavailable();
        }
        for (JsonNode item : items) {
          if (validIdentity(item)) {
            String id = item.path("Id").asText();
            uniqueItems.putIfAbsent(
                id,
                new EmbyDtos.MediaItem(
                    id,
                    item.path("Name").asText(),
                    Math.max(0, item.path("RunTimeTicks").asLong() / TICKS_PER_MILLISECOND),
                    uniqueItems.size() + 1,
                    item.path("Type").asText("Video"),
                    EmbySourceFingerprint.fromPath(item.path("Path").asText(null))));
          }
        }
        startIndex += pageItemCount;
      } while (startIndex < totalRecordCount);
      return List.copyOf(uniqueItems.values());
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  /** 父节点存在性必须单独验证，避免已删除节点的空结果清空本地快照。 */
  private void validateParent(RuntimeIntegrationSettings.Emby configuration, String parentItemId) {
    try {
      client(configuration)
          .get()
          .uri(
              builder ->
                  builder
                      .path("/Users/{userId}/Items/{parentItemId}")
                      .build(configuration.userId(), parentItemId))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
          || exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
        throw new BusinessException(
            HttpStatus.CONFLICT, "EMBY_PARENT_NOT_FOUND", "Emby 媒体来源不存在或当前用户无权访问");
      }
      throw unavailable();
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private String requestPage(
      RuntimeIntegrationSettings.Emby configuration, String parentItemId, int startIndex) {
    return client(configuration)
        .get()
        .uri(
            builder ->
                builder
                    .path("/Users/{userId}/Items")
                    .queryParam("ParentId", parentItemId)
                    .queryParam("Recursive", true)
                    .queryParam("IncludeItemTypes", "Movie,Episode,Video")
                    .queryParam("MediaTypes", "Video")
                    .queryParam("IsFolder", false)
                    .queryParam("Fields", "RunTimeTicks,Path,SortName")
                    .queryParam("SortBy", "SortName")
                    .queryParam("SortOrder", "Ascending")
                    .queryParam("StartIndex", startIndex)
                    .queryParam("Limit", pageSize)
                    .build(configuration.userId()))
        .retrieve()
        .body(String.class);
  }

  private RestClient client(RuntimeIntegrationSettings.Emby configuration) {
    return RestClient.builder()
        .baseUrl(configuration.baseUrl())
        .requestFactory(requestFactory)
        .defaultHeader("X-Emby-Token", configuration.apiKey())
        // 部分 Emby 前置代理返回不兼容的 deflate 数据，元数据请求直接禁用压缩。
        .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
        .build();
  }

  private RuntimeIntegrationSettings.Emby requiredConfiguration() {
    RuntimeIntegrationSettings.Emby configuration = properties.current();
    if (!configuration.configured()
        || configuration.userId() == null
        || configuration.userId().isBlank()) {
      throw unavailable();
    }
    return configuration;
  }

  private boolean validIdentity(JsonNode item) {
    return !item.path("Id").asText("").isBlank() && !item.path("Name").asText("").isBlank();
  }

  private BusinessException unavailable() {
    return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMBY_UNAVAILABLE", "媒体服务暂时不可用");
  }
}
