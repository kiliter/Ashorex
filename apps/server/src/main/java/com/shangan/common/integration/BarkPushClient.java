package com.shangan.common.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Bark 协议适配：个人和系统共用协议，但目的地由各自调用方独立提供。 */
@Component
public class BarkPushClient {
  /** 禁止重定向，防止可信源站将包含设备 Key 的请求转交其他主机。 */
  public boolean send(
      String baseUrl, String deviceKey, int timeoutSeconds, String title, String body) {
    return send(baseUrl, deviceKey, timeoutSeconds, title, body, true);
  }

  /** 由个人催办决定通知级别；普通通知不使用 critical，其他默认参数保持一致。 */
  public boolean send(
      String baseUrl,
      String deviceKey,
      int timeoutSeconds,
      String title,
      String body,
      boolean important) {
    try {
      var timeout = Duration.ofSeconds(timeoutSeconds);
      var factory =
          new JdkClientHttpRequestFactory(
              HttpClient.newBuilder()
                  .connectTimeout(timeout)
                  .followRedirects(HttpClient.Redirect.NEVER)
                  .build());
      factory.setReadTimeout(timeout);
      var result =
          RestClient.builder()
              .requestFactory(factory)
              .build()
              .post()
              .uri(baseUrl.replaceAll("/+$", "") + "/push")
              .body(
                  Map.of(
                      "device_key",
                      deviceKey,
                      "title",
                      title,
                      "body",
                      body,
                      "group",
                      "上岸",
                      "level",
                      important ? "critical" : "active",
                      "url",
                      "shangan://home"))
              .exchange(
                  (request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) return false;
                    var payload = response.bodyTo(Map.class);
                    return payload != null
                        && payload.get("code") instanceof Number code
                        && code.intValue() == 200;
                  });
      return Boolean.TRUE.equals(result);
    } catch (Exception ignored) {
      // 第三方响应、完整目的地和密钥不进入日志或错误响应。
      return false;
    }
  }
}
