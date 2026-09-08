package com.shangan.nag.application.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Server 酱渠道的可用性判定与 HTTP 协议契约。
 *
 * <p>推送基址已提为 {@code app.serverchan.base-url}，测试用 JDK 自带 HttpServer 充当假 Server 酱（与 Emby 契约测试同一做法），
 * 覆盖成功、未配置、非 2xx、超时、连接失败与业务码非 0 六个分支，并断言任何一条路径都不泄漏 SendKey。
 */
class ServerChanNagChannelTest {

  private static final Instant NOW = Instant.parse("2026-09-07T15:00:00Z");
  private static final String SEND_KEY = "SCT123456SECRET";

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  @Test
  @DisplayName("渠道类型固定为 SERVERCHAN")
  void 渠道类型固定() {
    assertThat(channel(settings("", true)).type()).isEqualTo(NagChannelType.SERVERCHAN);
  }

  @Test
  @DisplayName("没有配置 SendKey 时渠道不可用")
  void 未配置时不可用() {
    assertThat(channel(settings("", true)).available()).isFalse();
  }

  @Test
  @DisplayName("配置了 SendKey 但催办推送被关闭时渠道不可用")
  void 关闭催办推送时不可用() {
    assertThat(channel(settings(SEND_KEY, false)).available()).isFalse();
  }

  @Test
  @DisplayName("配置齐全且启用时渠道可用")
  void 配置齐全时可用() {
    assertThat(channel(settings(SEND_KEY, true)).available()).isTrue();
  }

  @Test
  @DisplayName("渠道不可用时直接返回失败，且失败详情不包含 SendKey 与催办正文")
  void 不可用时失败且脱敏() {
    NagChannel.DeliveryOutcome outcome = channel(settings("", true)).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.detail()).isEqualTo("Server 酱未配置或未启用");
    assertThat(outcome.detail()).doesNotContain(SEND_KEY);
  }

  @Test
  @DisplayName("推送成功：SendKey 拼进 /{sendKey}.send 路径，标题与正文以表单提交")
  void 推送成功() throws Exception {
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> contentType = new AtomicReference<>();
    AtomicReference<Map<String, String>> form = new AtomicReference<>();
    startServer(
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          form.set(readForm(exchange));
          respond(exchange, 200, "{\"code\":0,\"message\":\"\",\"data\":{\"pushid\":\"1\"}}");
        });

    NagChannel.DeliveryOutcome outcome =
        channel(settings(SEND_KEY, true), baseUrl()).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.status()).isEqualTo("SENT");
    assertThat(outcome.detail()).isEqualTo("已通过 Server 酱发送");
    assertThat(path.get()).isEqualTo("/" + SEND_KEY + ".send");
    assertThat(contentType.get()).startsWith("application/x-www-form-urlencoded");
    assertThat(form.get()).containsEntry("title", "上岸催办 · 小明").containsEntry("desp", "还有 3 项没做");
  }

  @Test
  @DisplayName("未配置 SendKey 时不发出任何 HTTP 请求")
  void 未配置时不发请求() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    startServer(
        exchange -> {
          requests.incrementAndGet();
          respond(exchange, 200, "{\"code\":0}");
        });

    NagChannel.DeliveryOutcome outcome =
        channel(settings("", true), baseUrl()).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(requests.get()).isZero();
  }

  @Test
  @DisplayName("催办推送被关闭时同样不发出 HTTP 请求")
  void 关闭时不发请求() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    startServer(
        exchange -> {
          requests.incrementAndGet();
          respond(exchange, 200, "{\"code\":0}");
        });

    NagChannel.DeliveryOutcome outcome =
        channel(settings(SEND_KEY, false), baseUrl()).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.detail()).isEqualTo("Server 酱未配置或未启用");
    assertThat(requests.get()).isZero();
  }

  @Test
  @DisplayName("HTTP 500 记为投递失败，详情只保留异常类型（Spring 的状态子类名）")
  void 服务端错误记为失败() throws Exception {
    startServer(exchange -> respond(exchange, 500, "{\"code\":500,\"message\":\"boom\"}"));

    NagChannel.DeliveryOutcome outcome =
        channel(settings(SEND_KEY, true), baseUrl()).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.detail()).isEqualTo("推送失败：InternalServerError");
    assertThat(outcome.detail()).doesNotContain("boom");
  }

  @Test
  @DisplayName("HTTP 400 也记为投递失败，不把远端正文透出")
  void 客户端错误记为失败() throws Exception {
    startServer(exchange -> respond(exchange, 400, "{\"code\":40001,\"message\":\"bad sendkey\"}"));

    NagChannel.DeliveryOutcome outcome =
        channel(settings(SEND_KEY, true), baseUrl()).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.detail()).isEqualTo("推送失败：BadRequest");
    assertThat(outcome.detail()).doesNotContain("bad sendkey");
  }

  @Test
  @DisplayName("读超时记为投递失败：超时秒数取运行时配置，不无限等待")
  void 读超时记为失败() throws Exception {
    startServer(
        exchange -> {
          try {
            // 故意超过 1 秒读超时；渠道必须自己中断而不是等到假服务返回。
            Thread.sleep(1500);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          respond(exchange, 200, "{\"code\":0}");
        });

    NagChannel.DeliveryOutcome outcome =
        channel(settings(SEND_KEY, true, 1), baseUrl()).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.detail()).isEqualTo("推送失败：ResourceAccessException");
  }

  @Test
  @DisplayName("连接失败记为投递失败，详情不包含目标地址")
  void 连接失败记为失败() throws Exception {
    // 先占端口取得一个确定可用的地址，再关闭，让连接必然被拒绝。
    startServer(exchange -> respond(exchange, 200, "{\"code\":0}"));
    String deadBaseUrl = baseUrl();
    stopServer();

    NagChannel.DeliveryOutcome outcome =
        channel(settings(SEND_KEY, true), deadBaseUrl).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.detail()).isEqualTo("推送失败：ResourceAccessException");
    assertThat(outcome.detail()).doesNotContain("127.0.0.1").doesNotContain(SEND_KEY);
  }

  @Test
  @DisplayName("HTTP 200 但业务码非 0 记为投递失败，不透出远端提示文案")
  void 业务码非零记为失败() throws Exception {
    startServer(
        exchange ->
            respond(exchange, 200, "{\"code\":40001,\"message\":\"bad pushtoken\",\"data\":null}"));

    NagChannel.DeliveryOutcome outcome =
        channel(settings(SEND_KEY, true), baseUrl()).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.detail()).isEqualTo("推送失败：Server 酱返回业务错误");
    assertThat(outcome.detail()).doesNotContain("bad pushtoken");
  }

  @Test
  @DisplayName("响应体缺少 code 字段时按成功处理，避免官方格式微调造成误报")
  void 响应缺少业务码按成功处理() throws Exception {
    startServer(exchange -> respond(exchange, 200, ""));

    NagChannel.DeliveryOutcome outcome =
        channel(settings(SEND_KEY, true), baseUrl()).deliver(nag(), "小明");

    assertThat(outcome.succeeded()).isTrue();
  }

  /**
   * AGENTS.md 硬约束：SendKey 不得进入日志或投递详情。
   *
   * <p>这里给渠道 Logger 挂一个 Logback 内存 Appender，跑完四条失败路径后逐一断言日志事件与 detail 都不含明文 SendKey； 同时断言日志里连
   * {@code .send} 路径片段都没有，防止后续有人把完整 URL 记进日志。
   */
  @Test
  @DisplayName("任何失败路径的日志与投递详情都不出现 SendKey 明文")
  void 日志与详情不泄漏SendKey() throws Exception {
    Logger channelLogger = (Logger) LoggerFactory.getLogger(ServerChanNagChannel.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    channelLogger.addAppender(appender);
    try {
      // 1) 非 2xx：底层异常消息本身带 URL，实现只能记异常类型。
      startServer(exchange -> respond(exchange, 500, "{\"code\":500}"));
      NagChannel.DeliveryOutcome serverError =
          channel(settings(SEND_KEY, true), baseUrl()).deliver(nag(), "小明");
      stopServer();

      // 2) 业务码非 0。
      startServer(exchange -> respond(exchange, 200, "{\"code\":40001}"));
      NagChannel.DeliveryOutcome businessError =
          channel(settings(SEND_KEY, true), baseUrl()).deliver(nag(), "小明");
      String deadBaseUrl = baseUrl();
      stopServer();

      // 3) 连接失败：ResourceAccessException 的原始消息含完整 URL 与 SendKey。
      NagChannel.DeliveryOutcome connectError =
          channel(settings(SEND_KEY, true), deadBaseUrl).deliver(nag(), "小明");

      // 4) 未配置：不发请求也不得回显。
      NagChannel.DeliveryOutcome notConfigured =
          channel(settings("", true), deadBaseUrl).deliver(nag(), "小明");

      assertThat(serverError.succeeded()).isFalse();
      assertThat(businessError.succeeded()).isFalse();
      assertThat(connectError.succeeded()).isFalse();
      assertThat(notConfigured.succeeded()).isFalse();
      assertThat(
              List.of(
                  serverError.detail(),
                  businessError.detail(),
                  connectError.detail(),
                  notConfigured.detail()))
          .allSatisfy(detail -> assertThat(detail).doesNotContain(SEND_KEY));

      // 三条会写日志的失败路径都必须留下告警，且没有一条携带密钥、URL 或堆栈。
      assertThat(appender.list).hasSize(3);
      for (ILoggingEvent event : appender.list) {
        String rendered = event.getFormattedMessage();
        assertThat(rendered).doesNotContain(SEND_KEY);
        assertThat(rendered).doesNotContain(".send");
        assertThat(rendered).doesNotContain("127.0.0.1");
        assertThat(event.getThrowableProxy()).isNull();
      }
    } finally {
      channelLogger.detachAppender(appender);
      appender.stop();
    }
  }

  private ServerChanNagChannel channel(RuntimeIntegrationSettings settings) {
    return channel(settings, ServerChanNagChannel.DEFAULT_BASE_URL);
  }

  private ServerChanNagChannel channel(RuntimeIntegrationSettings settings, String baseUrl) {
    IntegrationSettingsProvider provider = () -> settings;
    return new ServerChanNagChannel(provider, baseUrl);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static RuntimeIntegrationSettings settings(String sendKey, boolean nagEnabled) {
    return settings(sendKey, nagEnabled, 8);
  }

  private static RuntimeIntegrationSettings settings(
      String sendKey, boolean nagEnabled, int timeoutSeconds) {
    return new RuntimeIntegrationSettings(
        RuntimeIntegrationSettings.Emby.defaults(),
        List.of(),
        new RuntimeIntegrationSettings.ServerChan(sendKey, timeoutSeconds, nagEnabled, false),
        RuntimeIntegrationSettings.Features.defaults(),
        NOW.toEpochMilli());
  }

  private static Nag nag() {
    return new Nag(
        "nag-1",
        "user-1",
        LocalDate.of(2026, 9, 7),
        1,
        NagTrigger.AUTO,
        null,
        95,
        3,
        "还有 3 项没做",
        true,
        NagStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        NOW);
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

  /** 把 application/x-www-form-urlencoded 请求体解成单值映射。 */
  private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
    String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> values = new LinkedHashMap<>();
    if (raw.isBlank()) {
      return values;
    }
    for (String pair : raw.split("&")) {
      String[] parts = pair.split("=", 2);
      values.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return values;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
  }

  /** 测试用的假 Server 酱处理器。 */
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
