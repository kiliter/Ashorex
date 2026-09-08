package com.shangan.nag.application;

import com.shangan.nag.domain.NagTransportMode;
import com.shangan.nag.infrastructure.NagPolicyRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久化全局通知模式；不改变扫描、在线与催办状态机。 */
@Service
public class NagTransportService {
  private final NagPolicyRepository policies;
  private final Clock clock;

  public NagTransportService(NagPolicyRepository policies, Clock clock) {
    this.policies = policies;
    this.clock = clock;
  }

  /** 未配置的新实例默认启用 SSE。 */
  public NagTransportMode current() {
    return policies.findTransportMode().orElse(NagTransportMode.SSE);
  }

  /** 后台保存后，下次客户端心跳读取新模式，无需重启。 */
  @Transactional
  public void save(NagTransportMode mode) {
    policies.saveTransportMode(mode, clock.instant());
  }
}
