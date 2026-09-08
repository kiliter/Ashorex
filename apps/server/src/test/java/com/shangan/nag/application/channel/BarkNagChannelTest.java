package com.shangan.nag.application.channel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangan.nag.application.BarkSettingsService;
import com.shangan.nag.domain.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 使用本地 HTTP 假服务验证 Bark 协议与个人收件目标，不发送真实通知。 */
class BarkNagChannelTest {
  @Test
  void 发送个人密钥三项默认参数和自定义标题() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var received = new AtomicReference<String>();
    server.createContext(
        "/push",
        exchange -> {
          received.set(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          byte[] body = "{\"code\":200}".getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var settings = mock(BarkSettingsService.class);
      when(settings.get("learner"))
          .thenReturn(
              new BarkSettings(
                  "http://127.0.0.1:" + server.getAddress().getPort(), "personal-key", true));
      var nag =
          new Nag(
                  "nag",
                  "learner",
                  LocalDate.of(2026, 9, 8),
                  1,
                  NagTrigger.MANUAL,
                  null,
                  10,
                  2,
                  "附加说明",
                  true,
                  NagStatus.PENDING,
                  null,
                  null,
                  null,
                  null,
                  null,
                  Instant.EPOCH)
              .withTitle("请开始学习");
      var endpoints = mock(com.shangan.common.integration.BarkEndpointPolicy.class);
      when(endpoints.requirePersonalEndpoint(anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      var result =
          new BarkNagChannel(
                  settings, new com.shangan.common.integration.BarkPushClient(), endpoints)
              .deliver(nag, "小明");
      assertThat(result.succeeded()).isTrue();
      var json = new ObjectMapper().readTree(received.get());
      assertThat(json.path("device_key").asText()).isEqualTo("personal-key");
      assertThat(json.path("group").asText()).isEqualTo("上岸");
      assertThat(json.path("level").asText()).isEqualTo("critical");
      assertThat(json.path("url").asText()).isEqualTo("shangan://home");
      assertThat(json.path("title").asText()).isEqualTo("请开始学习");
      assertThat(json.path("body").asText()).isEqualTo("附加说明");
      assertThat(result.detail()).doesNotContain("personal-key");
    } finally {
      server.stop(0);
    }
  }
}
