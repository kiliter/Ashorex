package com.shangan.media.emby;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
  private static final Set<String> SUPPORTED_SOURCE_ITEM_TYPES =
      Set.of("collectionfolder", "series", "folder", "movie");

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

  /** 弹窗打开后分页读取绑定媒体库内的全部 Series/Movie；关键字仅用于可选的服务端过滤。 */
  @Override
  public List<EmbyDtos.MediaSource> searchSources(String query) {
    RuntimeIntegrationSettings runtime = requiredSettings();
    RuntimeIntegrationSettings.Emby configuration = runtime.emby();
    String normalizedQuery = query == null ? "" : query.trim();
    if (runtime.embyLibraries().isEmpty()) {
      return List.of();
    }
    try {
      Map<String, EmbyDtos.MediaSource> uniqueSources = new LinkedHashMap<>();
      for (RuntimeIntegrationSettings.EmbyLibrary library : runtime.embyLibraries()) {
        String includeItemTypes = includeItemTypes(library.contentType());
        int startIndex = 0;
        int totalRecordCount;
        do {
          String body =
              requestSourceSearch(
                  configuration, library.id(), normalizedQuery, includeItemTypes, startIndex);
          JsonNode response = objectMapper.readTree(body);
          JsonNode items = response.path("Items");
          totalRecordCount = response.path("TotalRecordCount").asInt(items.size());
          int pageItemCount = items.size();
          if (pageItemCount == 0 && startIndex < totalRecordCount) {
            throw unavailable();
          }
          for (JsonNode item : items) {
            EmbyDtos.MediaSource source = sourceFromNode(item);
            if (source != null && includedType(source.itemType(), includeItemTypes)) {
              uniqueSources.putIfAbsent(source.id(), source);
            }
          }
          startIndex += pageItemCount;
        } while (startIndex < totalRecordCount);
      }
      return uniqueSources.values().stream()
          .sorted(
              Comparator.comparing(EmbyDtos.MediaSource::name, String.CASE_INSENSITIVE_ORDER)
                  .thenComparing(EmbyDtos.MediaSource::id))
          .toList();
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  /** 提交批量建课或重新绑定前重新校验来源存在、可访问且类型受支持。 */
  @Override
  public EmbyDtos.MediaSource getSource(String itemId) {
    RuntimeIntegrationSettings.Emby configuration = requiredConfiguration();
    try {
      String body =
          client(configuration)
              .get()
              .uri(
                  builder ->
                      builder
                          .path("/Users/{userId}/Items/{itemId}")
                          .queryParam("Fields", "ParentId")
                          .build(configuration.userId(), itemId))
              .retrieve()
              .body(String.class);
      EmbyDtos.MediaSource source = sourceFromNode(objectMapper.readTree(body));
      if (source == null) {
        throw new BusinessException(
            HttpStatus.BAD_REQUEST,
            "EMBY_SOURCE_TYPE_UNSUPPORTED",
            "请选择媒体库、Series、Folder 或 Movie 作为课程来源");
      }
      return source;
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
          || exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
        throw parentNotFound();
      }
      throw unavailable();
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  /** 验证父节点后，在配置用户作用域内分页递归读取全部电影、剧集和普通视频。 */
  @Override
  public List<EmbyDtos.MediaItem> listChildren(String parentItemId) {
    RuntimeIntegrationSettings.Emby configuration = requiredConfiguration();
    JsonNode parent = readParent(configuration, parentItemId);
    try {
      if (parent.path("Type").asText("").equalsIgnoreCase("Movie")) {
        return List.of(mediaItemFromNode(parent, 1));
      }
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
            uniqueItems.putIfAbsent(id, mediaItemFromNode(item, uniqueItems.size() + 1));
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
  private JsonNode readParent(RuntimeIntegrationSettings.Emby configuration, String parentItemId) {
    try {
      String body =
          client(configuration)
              .get()
              .uri(
                  builder ->
                      builder
                          .path("/Users/{userId}/Items/{parentItemId}")
                          .queryParam("Fields", "RunTimeTicks,Path")
                          .build(configuration.userId(), parentItemId))
              .retrieve()
              .body(String.class);
      return objectMapper.readTree(body);
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

  private String requestSourceSearch(
      RuntimeIntegrationSettings.Emby configuration,
      String libraryId,
      String query,
      String includeItemTypes,
      int startIndex) {
    return client(configuration)
        .get()
        .uri(
            builder ->
                builder
                    .path("/Users/{userId}/Items")
                    .queryParam("ParentId", libraryId)
                    .queryParam("Recursive", true)
                    .queryParamIfPresent(
                        "SearchTerm", query.isBlank() ? Optional.empty() : Optional.of(query))
                    .queryParam("IncludeItemTypes", includeItemTypes)
                    .queryParam("Fields", "ParentId,SortName")
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
    return requiredSettings().emby();
  }

  private RuntimeIntegrationSettings requiredSettings() {
    RuntimeIntegrationSettings settings = properties.snapshot();
    RuntimeIntegrationSettings.Emby configuration = settings.emby();
    if (!configuration.configured()
        || configuration.userId() == null
        || configuration.userId().isBlank()) {
      throw unavailable();
    }
    return settings;
  }

  private boolean validIdentity(JsonNode item) {
    return !item.path("Id").asText("").isBlank() && !item.path("Name").asText("").isBlank();
  }

  private EmbyDtos.MediaSource sourceFromNode(JsonNode item) {
    if (!validIdentity(item)) {
      return null;
    }
    String itemType = item.path("Type").asText("");
    if (!SUPPORTED_SOURCE_ITEM_TYPES.contains(itemType.toLowerCase(Locale.ROOT))) {
      return null;
    }
    String collectionType = item.path("CollectionType").asText("");
    if (itemType.equalsIgnoreCase("CollectionFolder")
        && !SUPPORTED_LIBRARY_TYPES.contains(collectionType.toLowerCase(Locale.ROOT))) {
      return null;
    }
    return new EmbyDtos.MediaSource(
        item.path("Id").asText(),
        item.path("Name").asText(),
        itemType,
        collectionType,
        item.path("ParentId").asText(""));
  }

  /** 把媒体库绑定类型转换为 Emby Items API 接受的 IncludeItemTypes。 */
  private String includeItemTypes(RuntimeIntegrationSettings.EmbyLibraryType contentType) {
    return switch (contentType) {
      case SERIES -> "Series";
      case MOVIE -> "Movie";
      case MIXED -> "Series,Movie";
    };
  }

  /** 对上游结果再次做类型白名单过滤，避免 Emby 版本差异把 Folder 混入默认候选。 */
  private boolean includedType(String itemType, String includeItemTypes) {
    for (String included : includeItemTypes.split(",")) {
      if (included.equalsIgnoreCase(itemType)) {
        return true;
      }
    }
    return false;
  }

  /** 将一个可播放项转换为课程课时安全快照，不向业务层暴露物理路径。 */
  private EmbyDtos.MediaItem mediaItemFromNode(JsonNode item, int indexNumber) {
    if (!validIdentity(item)) {
      throw unavailable();
    }
    return new EmbyDtos.MediaItem(
        item.path("Id").asText(),
        item.path("Name").asText(),
        Math.max(0, item.path("RunTimeTicks").asLong() / TICKS_PER_MILLISECOND),
        indexNumber,
        item.path("Type").asText("Video"),
        EmbySourceFingerprint.fromPath(item.path("Path").asText(null)));
  }

  private BusinessException parentNotFound() {
    return new BusinessException(
        HttpStatus.CONFLICT, "EMBY_PARENT_NOT_FOUND", "Emby 媒体来源不存在或当前用户无权访问");
  }

  private BusinessException unavailable() {
    return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMBY_UNAVAILABLE", "媒体服务暂时不可用");
  }
}
