package com.shangan.presence.application;

import com.shangan.presence.infrastructure.UserPresenceRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * 记录「有效操作」的统一入口。
 *
 * <p>有效操作包括进度上报、完成、勾选、附件上传、创建 / 删除 Todo、催办回应。心跳**不是**有效操作。
 */
@Component
public class EffectiveActionRecorder {

  private final UserPresenceRepository presence;
  private final Clock clock;

  public EffectiveActionRecorder(UserPresenceRepository presence, Clock clock) {
    this.presence = presence;
    this.clock = clock;
  }

  public void record(String userId) {
    presence.recordEffectiveAction(userId, clock.instant());
  }
}
