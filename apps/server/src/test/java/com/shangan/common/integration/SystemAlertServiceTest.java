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
  void 测试使用已保存系统配置且不受异常开关限制() {
    var client = org.mockito.Mockito.mock(BarkPushClient.class);
    var config =
        new RuntimeIntegrationSettings(
            null,
            List.of(),
            null,
            null,
            0,
            new RuntimeIntegrationSettings.Bark(
                "https://api.day.app", "system-test-key", false, 8));
    var service = new SystemAlertService(() -> config, Clock.systemUTC(), client, "/tmp/not-used");
    org.mockito.Mockito.when(
            client.send(
                "https://api.day.app",
                "system-test-key",
                8,
                "上岸系统测试通知",
                "这是一条系统 Bark 测试通知，用于确认系统异常通知的接收设备。"))
        .thenReturn(true);
    assertThat(service.testNotification().ok()).isTrue();
    org.mockito.Mockito.verify(client)
        .send(
            "https://api.day.app",
            "system-test-key",
            8,
            "上岸系统测试通知",
            "这是一条系统 Bark 测试通知，用于确认系统异常通知的接收设备。");
  }

  @Test
  void 未配置不发送且失败不泄露目的地() {
    var client = org.mockito.Mockito.mock(BarkPushClient.class);
    var config =
        new RuntimeIntegrationSettings(
            null,
            List.of(),
            null,
            null,
            0,
            new RuntimeIntegrationSettings.Bark("https://api.day.app", "", false, 8));
    var service = new SystemAlertService(() -> config, Clock.systemUTC(), client, "/tmp/not-used");
    assertThat(service.testNotification().status()).isEqualTo("未配置");
    org.mockito.Mockito.verifyNoInteractions(client);
    var configured =
        new RuntimeIntegrationSettings(
            null,
            List.of(),
            null,
            null,
            0,
            new RuntimeIntegrationSettings.Bark("https://api.day.app", "system-test-key", true, 8));
    var failed =
        new SystemAlertService(() -> configured, Clock.systemUTC(), client, "/tmp/not-used")
            .testNotification();
    assertThat(failed.ok()).isFalse();
    assertThat(failed.message()).doesNotContain("system-test-key", "https://api.day.app");
  }

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
