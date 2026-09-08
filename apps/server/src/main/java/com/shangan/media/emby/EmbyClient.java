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
  private final HttpClient httpClient;

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
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
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
        // V2 书籍只预留模型，不向 Emby 发起书籍发现请求。
        if (library.contentType() == RuntimeIntegrationSettings.EmbyLibraryType.BOOK) continue;
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
  /**
   * 读取来源的完整元数据；Genres / Tags / People 是移动端筛选维度的唯一来源。
   *
   * <p>请求显式声明 Fields，避免依赖不同 Emby 版本的默认字段集。
   */
  @Override
  public EmbyDtos.SourceMetadata getSourceMetadata(String itemId) {
    RuntimeIntegrationSettings.Emby configuration = requiredConfiguration();
    try {
      String body =
          client(configuration)
              .get()
              .uri(
                  builder ->
                      builder
                          .path("/Users/{userId}/Items/{itemId}")
                          .queryParam(
                              "Fields",
                              "ParentId,Overview,Genres,Tags,TagItems,People,ProductionYear")
                          .build(configuration.userId(), itemId))
              .retrieve()
              .body(String.class);
      JsonNode item = objectMapper.readTree(body);
      return new EmbyDtos.SourceMetadata(
          item.path("Id").asText(itemId),
          item.path("Name").asText(""),
          item.path("Overview").asText(""),
          item.hasNonNull("ProductionYear") ? item.path("ProductionYear").asInt() : null,
          textList(item.path("Genres")),
          mergedTags(item),
          people(item.path("People")));
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

  /** Emby 不同版本分别使用 Tags 或 TagItems，两者合并去重后作为本地标签快照。 */
  private List<String> mergedTags(JsonNode item) {
    java.util.LinkedHashSet<String> tags =
        new java.util.LinkedHashSet<>(textList(item.path("Tags")));
    for (JsonNode node : item.path("TagItems")) {
      String name = node.path("Name").asText("").trim();
      if (!name.isEmpty()) {
        tags.add(name);
      }
    }
    return List.copyOf(tags);
  }

  private List<String> textList(JsonNode array) {
    java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
    for (JsonNode node : array) {
      String value = node.asText("").trim();
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return List.copyOf(values);
  }

  private List<EmbyDtos.PersonRef> people(JsonNode array) {
    java.util.LinkedHashMap<String, EmbyDtos.PersonRef> people = new java.util.LinkedHashMap<>();
    for (JsonNode node : array) {
      String name = node.path("Name").asText("").trim();
      if (name.isEmpty()) {
        continue;
      }
      people.putIfAbsent(name, new EmbyDtos.PersonRef(name, node.path("Type").asText("")));
    }
    return List.copyOf(people.values());
  }

  @Override
  public List<EmbyDtos.MediaItem> listChildren(String parentItemId) {
    RuntimeIntegrationSettings.Emby configuration = requiredConfiguration();
    JsonNode parent = readParent(configuration, parentItemId);
    try {
      if (parent.path("Type").asText("").equalsIgnoreCase("Movie")) {
        return resolveIndexNumbers(List.of(mediaItemFromNode(parent)));
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
            uniqueItems.putIfAbsent(id, mediaItemFromNode(item));
          }
        }
        startIndex += pageItemCount;
      } while (startIndex < totalRecordCount);
      return resolveIndexNumbers(List.copyOf(uniqueItems.values()));
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  /**
   * 为整份远端快照定稿课时序号。
   *
   * <p>规则（见 ADR-0034）：
   *
   * <ul>
   *   <li>Emby 给出 {@code IndexNumber} 的条目直接采用它。Episode 的 IndexNumber 就是集号，是远端稳定编号，
   *       不随同批次里其他条目的增删而变化。
   *   <li>缺失 {@code IndexNumber} 的条目（常见于 Movie 与散装 Video）才回退到远端列表位置，且统一排在 已知集号最大值之后，保证同一课程内不与集号撞号。
   *   <li>全部条目都没有 {@code IndexNumber} 时，最大值为 0，退化为 1、2、3……与历史行为一致。
   * </ul>
   *
   * <p>绝不能用「列表位置」直接当序号：远端下架一集会让其后所有条目整体前移，既改变学习者看到的 顺序，又与保留旧序号的下架课时撞号。
   */
  private List<EmbyDtos.MediaItem> resolveIndexNumbers(List<EmbyDtos.MediaItem> items) {
    int maxKnown = 0;
    for (EmbyDtos.MediaItem item : items) {
      maxKnown = Math.max(maxKnown, item.indexNumber());
    }
    List<EmbyDtos.MediaItem> resolved = new ArrayList<>(items.size());
    int nextFallback = maxKnown;
    for (EmbyDtos.MediaItem item : items) {
      if (item.indexNumber() > 0) {
        resolved.add(item);
        continue;
      }
      nextFallback++;
      resolved.add(
          new EmbyDtos.MediaItem(
              item.id(),
              item.title(),
              item.durationMs(),
              nextFallback,
              item.itemType(),
              item.sourceFingerprint()));
    }
    return List.copyOf(resolved);
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
                          .queryParam("Fields", "RunTimeTicks,Path,IndexNumber")
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
                    // IndexNumber 必须显式声明：它是课时序号的唯一权威来源，
                    // 不能依赖不同 Emby 版本的默认字段集（见 ADR-0034）。
                    .queryParam("Fields", "RunTimeTicks,Path,SortName,IndexNumber")
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

  /** 固定图片端点，禁止透传 URL；最多读 2 MiB，拒绝 HTML/SVG 和上游重定向。 */
  @Override
  public EmbyDtos.Cover readCover(String itemId) {
    RuntimeIntegrationSettings.Emby configuration = requiredConfiguration();
    try {
      return client(configuration)
          .get()
          .uri(
              builder ->
                  builder
                      .path("/Items/{itemId}/Images/Primary")
                      .queryParam("MaxWidth", 160)
                      .queryParam("MaxHeight", 160)
                      .build(itemId))
          .exchange(
              (request, response) -> {
                String type =
                    response.getHeaders().getContentType() == null
                        ? ""
                        : response.getHeaders().getContentType().toString();
                if (!response.getStatusCode().is2xxSuccessful()
                    || !Set.of("image/jpeg", "image/png", "image/webp").contains(type)) {
                  throw new BusinessException(HttpStatus.NOT_FOUND, "EMBY_COVER_NOT_FOUND", "暂无封面");
                }
                byte[] bytes = response.getBody().readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024) throw unavailable();
                return new EmbyDtos.Cover(bytes, type);
              });
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  /** 每个请求使用本轮配置的超时，独立工厂避免并发请求互相改写超时。 */
  private RestClient client(RuntimeIntegrationSettings.Emby configuration) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(configuration.timeoutSeconds()));
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
        item.path("ParentId").asText(""),
        item.path("ImageTags").hasNonNull("Primary"));
  }

  /** 把媒体库绑定类型转换为 Emby Items API 接受的 IncludeItemTypes。 */
  private String includeItemTypes(RuntimeIntegrationSettings.EmbyLibraryType contentType) {
    return switch (contentType) {
      case SERIES -> "Series";
      case MOVIE -> "Movie";
      case MIXED -> "Series,Movie";
      case BOOK -> "Book";
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

  /**
   * 将一个可播放项转换为课程课时安全快照，不向业务层暴露物理路径。
   *
   * <p>序号只取远端 {@code IndexNumber}；缺失时置 0 表示「未知」，由 {@link #resolveIndexNumbers} 统一定稿。
   */
  private EmbyDtos.MediaItem mediaItemFromNode(JsonNode item) {
    if (!validIdentity(item)) {
      throw unavailable();
    }
    return new EmbyDtos.MediaItem(
        item.path("Id").asText(),
        item.path("Name").asText(),
        Math.max(0, item.path("RunTimeTicks").asLong() / TICKS_PER_MILLISECOND),
        remoteIndexNumber(item),
        item.path("Type").asText("Video"),
        EmbySourceFingerprint.fromPath(item.path("Path").asText(null)));
  }

  /** Emby 的 IndexNumber 可能缺失、为 null 或非正数，这些情况一律视为「未知序号」返回 0。 */
  private int remoteIndexNumber(JsonNode item) {
    JsonNode node = item.path("IndexNumber");
    if (!node.isNumber()) {
      return 0;
    }
    int value = node.asInt(0);
    return Math.max(0, value);
  }

  private BusinessException parentNotFound() {
    return new BusinessException(
        HttpStatus.CONFLICT, "EMBY_PARENT_NOT_FOUND", "Emby 媒体来源不存在或当前用户无权访问");
  }

  private BusinessException unavailable() {
    return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMBY_UNAVAILABLE", "媒体服务暂时不可用");
  }
}
