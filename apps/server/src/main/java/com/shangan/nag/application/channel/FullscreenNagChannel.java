package com.shangan.nag.application.channel;

import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import org.springframework.stereotype.Component;

/** 全屏通知先登记待回应催办，再由事务提交后 SSE 通知；心跳始终兜底。 */
@Component
public class FullscreenNagChannel implements NagChannel {
  private final org.springframework.context.ApplicationEventPublisher events;

  public FullscreenNagChannel(org.springframework.context.ApplicationEventPublisher events) {
    this.events = events;
  }

  @Override
  public NagChannelType type() {
    return NagChannelType.FULLSCREEN;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public DeliveryOutcome deliver(Nag nag, String recipientDisplayName) {
    events.publishEvent(new com.shangan.nag.application.NagAvailable(nag.userId(), nag.id()));
    return DeliveryOutcome.sent("已加入客户端待拉取队列");
  }
}
