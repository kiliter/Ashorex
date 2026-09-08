package com.shangan.media.emby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 验证 Range 请求与必要响应头被流式代理保留。 */
class RangeProxyIntegrationTest {
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void forwardsRangeAndPreservesPartialResponseHeaders() throws Exception {
    AtomicReference<String> range = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/stream",
        exchange -> {
          range.set(exchange.getRequestHeaders().getFirst("Range"));
          byte[] body = "partial".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Range", "bytes 100-199/1000");
          exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
          exchange.getResponseHeaders().add("Content-Type", "video/mp4");
          exchange.sendResponseHeaders(206, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    EmbyStreamProxy proxy =
        new EmbyStreamProxy(
            new EmbyProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret", "user-1"));

    try (var response = proxy.open("/stream", "bytes=100-199", null)) {
      assertThat(response.statusCode()).isEqualTo(206);
      assertThat(response.headers().getFirst("Content-Range")).isEqualTo("bytes 100-199/1000");
      assertThat(response.headers().getFirst("Accept-Ranges")).isEqualTo("bytes");
      assertThat(response.body().readAllBytes())
          .isEqualTo("partial".getBytes(StandardCharsets.UTF_8));
    }
    assertThat(range.get()).isEqualTo("bytes=100-199");
  }

  /** Emby 失败正文不得经过播放器代理泄露；416 保留媒体范围协商所需响应头。 */
  @Test
  void rejectsUpstreamErrorsAndRemovesRangeErrorBody() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/error",
        exchange -> {
          byte[] body = "internal media path and credential".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/range",
        exchange -> {
          byte[] body = "internal media path".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Range", "bytes */1000");
          exchange.getResponseHeaders().set("Content-Type", "text/plain");
          exchange.sendResponseHeaders(416, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    EmbyStreamProxy proxy =
        new EmbyStreamProxy(
            new EmbyProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret", "user-1"));
    assertThatThrownBy(() -> proxy.open("/error", null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessage("媒体流暂时不可用");
    try (var response = proxy.open("/range", "bytes=2000-", null)) {
      assertThat(response.statusCode()).isEqualTo(416);
      assertThat(response.headers().getFirst("Content-Range")).isEqualTo("bytes */1000");
      assertThat(response.headers().getContentLength()).isZero();
      assertThat(response.body().readAllBytes()).isEmpty();
    }
  }
}
