package com.shangan.media.emby;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 管理后台「测试连接」依赖的 Emby 探测协议测试。
 *
 * <p>重点是三个边界：未配置不发请求也不算错误、非 2xx 与网络失败都收敛成稳定中文文案、 响应绝不携带 Base URL 或 API Key。
 */
class EmbyHealthServiceTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void reportsNotConfiguredWithoutSendingRequest() {
    EmbyHealthService health =
        new EmbyHealthService(new EmbyProperties("", "", ""), Clock.systemUTC());

    EmbyHealthService.Probe probe = health.probe();

    assertThat(probe.ok()).isFalse();
    assertThat(probe.status()).isEqualTo("未配置");
    assertThat(probe.message()).contains("尚未配置");
    assertThat(probe.latencyMs()).isZero();
  }

  @Test
  void reportsAvailableWhenSystemInfoReturnsSuccess() throws Exception {
    int port = startServer(200);
    EmbyHealthService health =
        new EmbyHealthService(
            new EmbyProperties("http://127.0.0.1:" + port, "test-token", "user-1"),
            // 固定时钟：耗时计算不依赖真实墙钟，断言稳定。
            Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC));

    EmbyHealthService.Probe probe = health.probe();

    assertThat(probe.ok()).isTrue();
    assertThat(probe.status()).isEqualTo("可用");
    assertThat(probe.message()).isEqualTo("Emby 连接正常，System/Info 可读。");
    assertThat(probe.latencyMs()).isZero();
  }

  @Test
  void reportsCredentialFailureWithoutLeakingHostOrKey() throws Exception {
    int port = startServer(401);
    String baseUrl = "http://127.0.0.1:" + port;
    EmbyHealthService health =
        new EmbyHealthService(
            new EmbyProperties(baseUrl, "wrong-token", "user-1"), Clock.systemUTC());

    EmbyHealthService.Probe probe = health.probe();

    assertThat(probe.ok()).isFalse();
    assertThat(probe.status()).isEqualTo("不可用");
    assertThat(probe.message()).isEqualTo("Emby 已响应但拒绝了凭据，请检查 API Key 是否有效。");
    assertThat(probe.message()).doesNotContain(baseUrl).doesNotContain("wrong-token");
  }

  @Test
  void reportsFriendlyMessageWhenHostUnreachable() {
    // 端口 1 上不会有监听者，触发连接失败分支。
    EmbyHealthService health =
        new EmbyHealthService(
            new EmbyProperties("http://127.0.0.1:1", "test-token", "user-1"), Clock.systemUTC());

    EmbyHealthService.Probe probe = health.probe();

    assertThat(probe.ok()).isFalse();
    assertThat(probe.status()).isEqualTo("不可用");
    assertThat(probe.message()).doesNotContain("127.0.0.1").doesNotContain("test-token");
    assertThat(probe.message()).endsWith("。");
  }

  @Test
  void statusDelegatesToProbe() throws Exception {
    int port = startServer(500);
    EmbyHealthService health =
        new EmbyHealthService(
            new EmbyProperties("http://127.0.0.1:" + port, "test-token", "user-1"),
            Clock.systemUTC());

    assertThat(health.status()).isEqualTo("不可用");
  }

  private int startServer(int statusCode) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/System/Info",
        exchange -> {
          exchange.sendResponseHeaders(statusCode, -1);
          exchange.close();
        });
    server.start();
    return server.getAddress().getPort();
  }
}
