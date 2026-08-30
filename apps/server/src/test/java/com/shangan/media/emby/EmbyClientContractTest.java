package com.shangan.media.emby;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 验证 Emby HTTP 契约、鉴权头与 ticks 到毫秒的转换。 */
class EmbyClientContractTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sendsServerSideTokenAndConvertsRuntimeTicks() throws Exception {
    AtomicReference<String> receivedToken = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/Items",
        exchange -> {
          receivedToken.set(exchange.getRequestHeaders().getFirst("X-Emby-Token"));
          byte[] body =
              """
              {"Items":[{"Id":"emby-ep-1","Name":"资料分析 01",\
              "RunTimeTicks":36000000000,"MediaType":"Video","IndexNumber":1}]}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    EmbyClient client =
        new EmbyClient(
            new EmbyProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "server-secret-token"),
            new ObjectMapper());

    EmbyDtos.MediaItem item = client.listChildren("parent-1").getFirst();

    assertThat(receivedToken.get()).isEqualTo("server-secret-token");
    assertThat(item.id()).isEqualTo("emby-ep-1");
    assertThat(item.durationMs()).isEqualTo(3_600_000L);
    assertThat(item.toString()).doesNotContain("server-secret-token");
  }
}
