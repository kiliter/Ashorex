package com.shangan.media.emby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** 验证媒体库发现、用户作用域、分页和混合视频类型的 Emby HTTP 契约。 */
class EmbyClientContractTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void springContainerUsesProductionConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          EmbyProperties.class,
          () -> new EmbyProperties("http://emby.invalid", "test-token", "user-1"));
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.register(EmbyClient.class);
      context.refresh();

      assertThat(context.getBean(EmbyClient.class)).isNotNull();
    }
  }

  @Test
  void listsLibrariesAndPaginatesMixedVideosInsideConfiguredUserScope() throws Exception {
    AtomicReference<String> receivedToken = new AtomicReference<>();
    List<Map<String, String>> itemQueries = new ArrayList<>();
    startServer(
        exchange -> {
          receivedToken.set(exchange.getRequestHeaders().getFirst("X-Emby-Token"));
          String path = exchange.getRequestURI().getPath();
          if (path.equals("/Users/user-1/Views")) {
            respond(
                exchange,
                200,
                """
                {"Items":[
                  {"Id":"library-1","Name":"考公","Type":"CollectionFolder"},
                  {"Id":"music-1","Name":"音乐","Type":"CollectionFolder","CollectionType":"music"},
                  {"Id":"photos-1","Name":"相册","Type":"CollectionFolder","CollectionType":"photos"}
                ],"TotalRecordCount":3}
                """);
            return;
          }
          if (path.equals("/Users/user-1/Items/library-1")) {
            respond(
                exchange,
                200,
                "{\"Id\":\"library-1\",\"Name\":\"考公\",\"Type\":\"CollectionFolder\",\"IsFolder\":true}");
            return;
          }
          if (path.equals("/Users/user-1/Items")) {
            Map<String, String> query = query(exchange);
            itemQueries.add(query);
            if (query.get("StartIndex").equals("0")) {
              respond(
                  exchange,
                  200,
                  """
                  {"Items":[
                    {"Id":"movie-1","Name":"资料分析","RunTimeTicks":36000000000,"Type":"Movie","Path":"/study/movie-1.mp4"},
                    {"Id":"episode-1","Name":"判断推理","RunTimeTicks":18000000000,"Type":"Episode","Path":"/study/episode-1.mp4"}
                  ],"TotalRecordCount":3}
                  """);
            } else {
              respond(
                  exchange,
                  200,
                  """
                  {"Items":[
                    {"Id":"video-1","Name":"常识导学","RunTimeTicks":6000000000,"Type":"Video","Path":"/study/video-1.mp4"}
                  ],"TotalRecordCount":3}
                  """);
            }
            return;
          }
          respond(exchange, 404, "not found");
        });
    EmbyClient client = client(2);

    List<EmbyDtos.MediaLibrary> libraries = client.listMediaLibraries();
    List<EmbyDtos.MediaItem> items = client.listChildren("library-1");

    assertThat(receivedToken.get()).isEqualTo("server-secret-token");
    assertThat(libraries).extracting(EmbyDtos.MediaLibrary::name).containsExactly("考公");
    assertThat(items)
        .extracting(EmbyDtos.MediaItem::itemType)
        .containsExactly("Movie", "Episode", "Video");
    assertThat(items).allSatisfy(item -> assertThat(item.sourceFingerprint()).isNotBlank());
    assertThat(itemQueries).hasSize(2);
    assertThat(itemQueries).extracting(query -> query.get("StartIndex")).containsExactly("0", "2");
    assertThat(itemQueries)
        .allSatisfy(
            query -> {
              assertThat(query.get("Limit")).isEqualTo("2");
              assertThat(query.get("ParentId")).isEqualTo("library-1");
              assertThat(query.get("Recursive")).isEqualTo("true");
              assertThat(query.get("IncludeItemTypes")).isEqualTo("Movie,Episode,Video");
              assertThat(query.get("MediaTypes")).isEqualTo("Video");
              assertThat(query.get("IsFolder")).isEqualTo("false");
            });
    assertThat(items.toString()).doesNotContain("server-secret-token", "/study/");
  }

  @Test
  void parentNotFoundReturnsStableErrorWithoutRequestingPages() throws Exception {
    startServer(exchange -> respond(exchange, 404, "not found"));

    assertThatThrownBy(() -> client(2).listChildren("deleted-library"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("EMBY_PARENT_NOT_FOUND"));
  }

  @Test
  void laterPageFailureDoesNotReturnPartialItems() throws Exception {
    startServer(
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          if (path.equals("/Users/user-1/Items/library-1")) {
            respond(exchange, 200, "{\"Id\":\"library-1\",\"IsFolder\":true}");
          } else if (query(exchange).getOrDefault("StartIndex", "0").equals("0")) {
            respond(
                exchange,
                200,
                "{\"Items\":[{\"Id\":\"one\",\"Name\":\"第一课\",\"Type\":\"Video\",\"Path\":\"/one.mp4\"}],\"TotalRecordCount\":2}");
          } else {
            respond(exchange, 500, "failed");
          }
        });

    assertThatThrownBy(() -> client(1).listChildren("library-1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("EMBY_UNAVAILABLE"));
  }

  private EmbyClient client(int pageSize) {
    return new EmbyClient(
        new EmbyProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(), "server-secret-token", "user-1"),
        new ObjectMapper(),
        pageSize);
  }

  private void startServer(ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            handler.handle(exchange);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  /** 把查询参数解码为单值映射，测试只使用不重复参数。 */
  private Map<String, String> query(HttpExchange exchange) {
    Map<String, String> values = new LinkedHashMap<>();
    String rawQuery = exchange.getRequestURI().getRawQuery();
    if (rawQuery == null || rawQuery.isBlank()) {
      return values;
    }
    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      values.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return values;
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
