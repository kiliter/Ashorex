package com.shangan.media.emby;

import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** 使用固定 Emby 主机执行短超时只读探测，返回值不会包含主机或 API Key。 */
@Service
public class EmbyHealthService {
  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final EmbyProperties properties;
  private final HttpClient client;

  public EmbyHealthService(EmbyProperties properties) {
    this.properties = properties;
    this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /** 返回适合后台展示的稳定中文状态，不向调用方传播第三方错误正文。 */
  public String status() {
    RuntimeIntegrationSettings.Emby configuration = properties.current();
    if (!configuration.configured()) {
      return "未配置";
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(configuration.baseUrl() + "/System/Info"))
              .timeout(TIMEOUT)
              .header("X-Emby-Token", configuration.apiKey())
              .GET()
              .build();
      int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status >= 200 && status < 300 ? "可用" : "不可用";
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return "不可用";
    }
  }
}
