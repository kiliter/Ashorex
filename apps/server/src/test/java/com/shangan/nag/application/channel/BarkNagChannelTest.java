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

/** 使用本地 HTTP 假服务验证 Bark 协议与个人收件目标，不发送真实通知。 */
class BarkNagChannelTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "MANUAL,Asia/Shanghai,2026-09-08T15:29:59Z,23:30,07:00,critical",
    "MANUAL,Asia/Shanghai,2026-09-08T15:30:00Z,23:30,07:00,active",
    "MANUAL,Asia/Shanghai,2026-09-08T16:00:00Z,23:30,07:00,active",
    "SUPERVISOR,Asia/Shanghai,2026-09-08T22:59:59Z,23:30,07:00,active",
    "MANUAL,Asia/Shanghai,2026-09-08T23:00:00Z,23:30,07:00,critical",
    "MANUAL,UTC,2026-09-08T16:00:00Z,23:30,07:00,critical",
    "MANUAL,UTC,2026-09-08T12:00:00Z,12:00,13:00,active",
    "MANUAL,UTC,2026-09-08T13:00:00Z,12:00,13:00,critical",
    "MANUAL,UTC,2026-09-08T12:00:00Z,12:00,12:00,critical",
    "AUTO,UTC,2026-09-08T12:00:00Z,12:00,13:00,critical"
  })
  void 按收件用户时区与免打扰发送通知且保留内容(
      NagTrigger trigger,
      String timezone,
      String instant,
      String quietStart,
      String quietEnd,
      String level)
      throws Exception {
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
                  trigger,
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
      var policies = mock(com.shangan.nag.application.NagPolicyResolver.class);
      var policy =
          new EffectiveNagPolicy(
              5,
              150,
              60,
              90,
              60,
              3,
              10,
              LocalTime.parse(quietStart),
              LocalTime.parse(quietEnd),
              1,
              5,
              true,
              true,
              "默认",
              true,
              true,
              true);
      if (trigger != NagTrigger.AUTO) when(policies.resolve("learner")).thenReturn(policy);
      var users = mock(com.shangan.identity.infrastructure.UserRepository.class);
      if (trigger != NagTrigger.AUTO)
        when(users.findById("learner"))
            .thenReturn(
                java.util.Optional.of(
                    new com.shangan.identity.domain.User(
                        "learner",
                        "learner",
                        "unused",
                        "小明",
                        timezone,
                        com.shangan.identity.domain.UserStatus.ACTIVE,
                        null,
                        java.util.Set.of())));
      var userTime =
          new com.shangan.identity.application.UserTimeService(
              users, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
      var result =
          new BarkNagChannel(
                  settings,
                  new com.shangan.common.integration.BarkPushClient(),
                  endpoints,
                  policies,
                  userTime)
              .deliver(nag, "小明");
      assertThat(result.succeeded()).isTrue();
      var json = new ObjectMapper().readTree(received.get());
      assertThat(json.path("device_key").asText()).isEqualTo("personal-key");
      assertThat(json.path("group").asText()).isEqualTo("上岸");
      assertThat(json.path("level").asText()).isEqualTo(level);
      assertThat(json.path("url").asText()).isEqualTo("shangan://home");
      assertThat(json.path("title").asText()).isEqualTo("请开始学习");
      assertThat(json.path("body").asText()).isEqualTo("附加说明");
      assertThat(result.detail()).doesNotContain("personal-key");
    } finally {
      server.stop(0);
    }
  }
}
