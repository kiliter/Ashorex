package com.shangan.common.integration;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 系统通知只使用系统配置，同一持续异常合并，恢复后允许再次通知。 */
class SystemAlertServiceTest {
  @Test
  void 同一故障去重并在恢复后重新通知() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var count = new AtomicInteger();
    var received = new java.util.concurrent.atomic.AtomicReference<String>();
    server.createContext(
        "/push",
        exchange -> {
          count.incrementAndGet();
          String request =
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
          received.set(request);
          byte[] body = "{\"code\":200}".getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var settings =
          new RuntimeIntegrationSettings(
              null,
              List.of(),
              null,
              null,
              0,
              new RuntimeIntegrationSettings.Bark(
                  "http://127.0.0.1:" + server.getAddress().getPort(), "system-key", true, 1));
      var service =
          new SystemAlertService(
              () -> settings,
              Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
              new BarkPushClient(),
              "/tmp/not-used");
      service.report("EMBY", true, "同步失败");
      service.report("EMBY", true, "同步失败");
      assertThat(count.get()).isEqualTo(1);
      assertThat(received.get()).contains("system-key").doesNotContain("personal-key");
      service.report("EMBY", false, "");
      service.report("EMBY", true, "同步失败");
      assertThat(count.get()).isEqualTo(2);
    } finally {
      server.stop(0);
    }
  }
}
