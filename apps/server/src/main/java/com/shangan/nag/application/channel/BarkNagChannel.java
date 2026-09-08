package com.shangan.nag.application.channel;

import com.shangan.nag.application.BarkSettingsService;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 个人催办只读取收件用户的 Bark 配置，绝不使用系统设备 Key。 */
@Component
public class BarkNagChannel implements NagChannel {
  private final BarkSettingsService settings;

  public BarkNagChannel(BarkSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public NagChannelType type() {
    return NagChannelType.BARK;
  }

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public boolean enabled(String userId) {
    return settings.get(userId).enabled();
  }

  @Override
  public boolean available(String userId) {
    var value = settings.get(userId);
    return value.enabled() && value.configured();
  }

  @Override
  public String unavailableReason() {
    return "用户 Bark 未配置或已关闭";
  }

  /** 设置连接和读取超时，只认可业务成功码，不记录密钥或远端正文。 */
  @Override
  public DeliveryOutcome deliver(Nag nag, String recipientDisplayName) {
    if (!available(nag.userId())) return DeliveryOutcome.failed(unavailableReason());
    var config = settings.get(nag.userId());
    try {
      var timeout = Duration.ofSeconds(8);
      var factory =
          new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
      factory.setReadTimeout(timeout);
      var response =
          RestClient.builder()
              .requestFactory(factory)
              .build()
              .post()
              .uri(config.baseUrl().replaceAll("/+$", "") + "/push")
              .body(
                  Map.of(
                      "device_key",
                      config.deviceKey(),
                      "title",
                      nag.title() == null ? "上岸催办 · " + recipientDisplayName : nag.title(),
                      "body",
                      nag.message(),
                      "group",
                      "上岸",
                      "level",
                      "critical",
                      "url",
                      "shangan://home"))
              .retrieve()
              .body(Map.class);
      return response != null
              && response.get("code") instanceof Number code
              && code.intValue() == 200
          ? DeliveryOutcome.sent("Bark 已发送")
          : DeliveryOutcome.failed("Bark 返回失败状态");
    } catch (Exception exception) {
      return DeliveryOutcome.failed("Bark 请求失败或超时");
    }
  }
}
