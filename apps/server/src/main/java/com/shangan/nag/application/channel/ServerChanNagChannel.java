package com.shangan.nag.application.channel;

import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Server 酱推送渠道。
 *
 * <p>SendKey 只在服务端使用，不出现在日志与投递明细里；调用必须设置超时。
 *
 * <p>推送基址由 {@code app.serverchan.base-url} 提供，默认值即 Server 酱生产地址；提取为配置项只为让协议测试能指向本地假服务，
 * 不构成让客户端或普通管理员改写目标主机的入口。
 */
@Component
public class ServerChanNagChannel implements NagChannel {

  /** Server 酱官方推送基址；生产默认值，不随环境变化。 */
  public static final String DEFAULT_BASE_URL = "https://sctapi.ftqq.com";

  private static final Logger log = LoggerFactory.getLogger(ServerChanNagChannel.class);

  private final IntegrationSettingsProvider settings;
  private final String baseUrl;

  public ServerChanNagChannel(
      IntegrationSettingsProvider settings,
      @Value("${app.serverchan.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
    this.settings = settings;
    this.baseUrl =
        trimTrailingSlash(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
  }

  @Override
  public NagChannelType type() {
    return NagChannelType.SERVERCHAN;
  }

  @Override
  public boolean available() {
    RuntimeIntegrationSettings.ServerChan configuration = settings.current().serverChan();
    return configuration.configured() && configuration.nagEnabled();
  }

  /** 区分「没填 SendKey」和「填了但把催办推送关了」，两者的处理动作完全不同。 */
  @Override
  public String unavailableReason() {
    RuntimeIntegrationSettings.ServerChan configuration = settings.current().serverChan();
    if (!configuration.configured()) {
      return "Server 酱未配置 SendKey";
    }
    if (!configuration.nagEnabled()) {
      return "Server 酱催办推送已关闭";
    }
    return "渠道不可用";
  }

  @Override
  public DeliveryOutcome deliver(Nag nag, String recipientDisplayName) {
    RuntimeIntegrationSettings.ServerChan configuration = settings.current().serverChan();
    if (!available()) {
      return DeliveryOutcome.failed("Server 酱未配置或未启用");
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("title", "上岸催办 · " + recipientDisplayName);
    form.add("desp", nag.message());
    try {
      JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
      requestFactory.setReadTimeout(Duration.ofSeconds(configuration.timeoutSeconds()));
      RestClient client = RestClient.builder().requestFactory(requestFactory).build();
      String body =
          client
              .post()
              .uri(baseUrl + "/" + configuration.sendKey() + ".send")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(String.class);
      // Server 酱即使 HTTP 200 也可能在正文里返回 code != 0（SendKey 失效、超频等）。
      if (!acceptedByRemote(body)) {
        log.warn("Server 酱投递被拒绝：远端返回非零业务码");
        return DeliveryOutcome.failed("推送失败：Server 酱返回业务错误");
      }
      return DeliveryOutcome.sent("已通过 Server 酱发送");
    } catch (RuntimeException exception) {
      // 只记录异常类型，不记录 URL、响应正文或堆栈，避免密钥或用户内容进入日志。
      log.warn("Server 酱投递失败：{}", exception.getClass().getSimpleName());
      return DeliveryOutcome.failed("推送失败：" + exception.getClass().getSimpleName());
    }
  }

  /**
   * 判定 Server 酱响应正文是否表示成功。
   *
   * <p>只按 {@code "code":0} 这一个契约字段判断；正文缺失或不含 code 字段时按成功处理，避免因官方响应格式微调造成误报失败。
   */
  private boolean acceptedByRemote(String body) {
    if (body == null || body.isBlank()) {
      return true;
    }
    int index = body.indexOf("\"code\"");
    if (index < 0) {
      return true;
    }
    String tail = body.substring(index + "\"code\"".length());
    int colon = tail.indexOf(':');
    if (colon < 0) {
      return true;
    }
    StringBuilder digits = new StringBuilder();
    for (int cursor = colon + 1; cursor < tail.length(); cursor++) {
      char character = tail.charAt(cursor);
      if (Character.isWhitespace(character) && digits.isEmpty()) {
        continue;
      }
      if (Character.isDigit(character) || (character == '-' && digits.isEmpty())) {
        digits.append(character);
        continue;
      }
      break;
    }
    return digits.isEmpty() || "0".contentEquals(digits);
  }

  private static String trimTrailingSlash(String value) {
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}
