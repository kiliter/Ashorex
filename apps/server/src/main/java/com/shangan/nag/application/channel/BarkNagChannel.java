package com.shangan.nag.application.channel;

import com.shangan.common.integration.BarkEndpointPolicy;
import com.shangan.common.integration.BarkPushClient;
import com.shangan.nag.application.BarkSettingsService;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import org.springframework.stereotype.Component;

/** 个人催办只读取收件用户的 Bark 配置，绝不使用系统设备 Key。 */
@Component
public class BarkNagChannel implements NagChannel {
  private final BarkSettingsService settings;

  private final BarkPushClient client;
  private final BarkEndpointPolicy endpoints;

  public BarkNagChannel(
      BarkSettingsService settings, BarkPushClient client, BarkEndpointPolicy endpoints) {
    this.settings = settings;
    this.client = client;
    this.endpoints = endpoints;
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
      String endpoint = endpoints.requirePersonalEndpoint(config.baseUrl());
      return client.send(
              endpoint,
              config.deviceKey(),
              8,
              nag.title() == null ? "上岸催办 · " + recipientDisplayName : nag.title(),
              nag.message())
          ? DeliveryOutcome.sent("Bark 已发送")
          : DeliveryOutcome.failed("Bark 请求失败或返回失败状态");
    } catch (Exception exception) {
      return DeliveryOutcome.failed("Bark 请求失败或超时");
    }
  }
}
