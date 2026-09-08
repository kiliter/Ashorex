package com.shangan.nag.application.channel;

import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;

/**
 * 催办投递渠道抽象。
 *
 * <p>新增渠道（邮件、企业微信等）只需新增实现类，不改扫描与降级逻辑（见 ADR-0026）。
 */
public interface NagChannel {

  NagChannelType type();

  /** 该渠道当前是否可用（配置齐全且被启用）。 */
  boolean available();

  /** 渠道选择开关与可发送状态分离，防止缺配置时偷偷换渠道。 */
  default boolean enabled(String userId) {
    return available();
  }

  /** 默认保持旧渠道的全局可用性；个人渠道按收件用户覆盖。 */
  default boolean available(String userId) {
    return available();
  }

  /**
   * 渠道不可用的原因，写入投递流水供后台排查。
   *
   * <p>只允许返回管理员可直接阅读的短语，不得包含密钥、目标地址与堆栈。
   */
  default String unavailableReason() {
    return "渠道不可用";
  }

  /** 投递一次；返回投递结果，失败不抛出以便记录流水并允许降级。 */
  DeliveryOutcome deliver(Nag nag, String recipientDisplayName);

  /** 投递结果；{@code detail} 不得包含密钥或第三方响应正文。 */
  record DeliveryOutcome(boolean succeeded, String status, String detail) {

    public static DeliveryOutcome sent(String detail) {
      return new DeliveryOutcome(true, "SENT", detail);
    }

    public static DeliveryOutcome failed(String detail) {
      return new DeliveryOutcome(false, "FAILED", detail);
    }
  }
}
