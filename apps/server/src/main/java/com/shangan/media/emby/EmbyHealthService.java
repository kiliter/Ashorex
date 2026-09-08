package com.shangan.media.emby;

import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** 使用固定 Emby 主机执行短超时只读探测，返回值不会包含主机或 API Key。 */
@Service
public class EmbyHealthService {
  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final EmbyProperties properties;
  private final HttpClient client;
  private final Clock clock;

  public EmbyHealthService(EmbyProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
    this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /** 返回适合后台展示的稳定中文状态，不向调用方传播第三方错误正文。 */
  public String status() {
    return probe().status();
  }

  /**
   * 执行一次 {@code GET /System/Info} 只读探测，供管理后台「测试连接」按钮使用。
   *
   * <p>职责：判断当前运行配置能否真正连上 Emby，并给出可直接展示的中文结论与耗时。
   *
   * <p>边界：未配置时直接返回「未配置」而不发请求，不视为错误；所有异常都被收敛成固定中文文案， 绝不把第三方响应正文、异常堆栈、Base URL 或 API Key
   * 透出到调用方。耗时以注入的 {@link Clock} 计算，不直接读系统时钟。
   */
  public Probe probe() {
    RuntimeIntegrationSettings.Emby configuration = properties.current();
    if (!configuration.configured()) {
      return new Probe(false, "未配置", "Emby 尚未配置：请先填写 Base URL、API Key 与 User ID 并保存，然后再测试连接。", 0L);
    }
    long startedAt = clock.millis();
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(configuration.baseUrl() + "/System/Info"))
              .timeout(TIMEOUT)
              .header("X-Emby-Token", configuration.apiKey())
              .GET()
              .build();
      int statusCode = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      long latencyMs = elapsedSince(startedAt);
      if (statusCode >= 200 && statusCode < 300) {
        return new Probe(true, "可用", "Emby 连接正常，System/Info 可读。", latencyMs);
      }
      if (statusCode == 401 || statusCode == 403) {
        return new Probe(false, "不可用", "Emby 已响应但拒绝了凭据，请检查 API Key 是否有效。", latencyMs);
      }
      if (statusCode == 404) {
        return new Probe(
            false, "不可用", "Emby 地址可达但未找到 System/Info，请确认 Base URL 是否指向 Emby 根路径。", latencyMs);
      }
      return new Probe(
          false, "不可用", "Emby 返回了非成功状态（HTTP " + statusCode + "），请检查服务端运行情况。", latencyMs);
    } catch (java.net.http.HttpTimeoutException exception) {
      return new Probe(
          false,
          "不可用",
          "连接 Emby 超时（" + TIMEOUT.toSeconds() + " 秒内无响应），请检查地址、端口与网络可达性。",
          elapsedSince(startedAt));
    } catch (java.net.ConnectException exception) {
      return new Probe(false, "不可用", "无法建立到 Emby 的连接，请确认地址、端口已开放且服务正在运行。", elapsedSince(startedAt));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new Probe(false, "不可用", "探测被中断，请稍后重试。", elapsedSince(startedAt));
    } catch (Exception exception) {
      // 统一收敛：地址非法、DNS 失败、TLS 失败等都可能带出主机信息，因此只给稳定文案。
      return new Probe(false, "不可用", "探测 Emby 失败，请检查 Base URL 格式与网络配置。", elapsedSince(startedAt));
    }
  }

  /** 用注入的 Clock 计算探测耗时，负值归零以防时钟回拨。 */
  private long elapsedSince(long startedAt) {
    return Math.max(0L, clock.millis() - startedAt);
  }

  /**
   * 探测结论。
   *
   * @param ok 是否连通成功
   * @param status 供状态点展示的稳定中文状态：未配置 / 可用 / 不可用
   * @param message 面向管理员的可操作中文文案，不含主机、密钥与异常细节
   * @param latencyMs 探测耗时毫秒；未配置时为 0
   */
  public record Probe(boolean ok, String status, String message, long latencyMs) {}
}
