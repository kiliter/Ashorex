package com.shangan.nag.application;

import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.infrastructure.NagPolicyRepository;
import java.time.Instant;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合并全局默认与按用户覆盖，得出生效策略。
 *
 * <p>覆盖只影响阈值与渠道类字段；扫描周期、在线宽限期与心跳间隔一律取全局值。
 */
@Service
public class NagPolicyResolver {

  private static final EffectiveNagPolicy BUILTIN_DEFAULT =
      new EffectiveNagPolicy(
          5,
          150,
          60,
          90,
          60,
          3,
          10,
          LocalTime.of(23, 30),
          LocalTime.of(7, 0),
          1,
          5,
          true,
          true,
          "{{user}} 今天还有 {{pending}} 项未完成，已经 {{idleMinutes}} 分钟没有任何操作。",
          true,
          true,
          true);

  private final NagPolicyRepository policies;

  public NagPolicyResolver(NagPolicyRepository policies) {
    this.policies = policies;
  }

  @Transactional(readOnly = true)
  public EffectiveNagPolicy global() {
    return policies
        .findGlobal()
        .map(NagPolicyRepository.StoredPolicy::policy)
        .orElse(BUILTIN_DEFAULT);
  }

  /** 某用户的生效策略。 */
  @Transactional(readOnly = true)
  public EffectiveNagPolicy resolve(String userId) {
    EffectiveNagPolicy globalPolicy = global();
    return policies
        .findByUser(userId)
        .map(NagPolicyRepository.StoredPolicy::policy)
        .map(override -> merge(globalPolicy, override))
        .orElse(globalPolicy);
  }

  @Transactional
  public void saveGlobal(EffectiveNagPolicy policy, Instant now) {
    policies.saveGlobal(policy, now);
  }

  @Transactional
  public void saveOverride(String userId, EffectiveNagPolicy policy, Instant now) {
    policies.saveUserOverride(userId, policy, now);
  }

  @Transactional
  public void removeOverride(String userId) {
    policies.deleteUserOverride(userId);
  }

  @Transactional(readOnly = true)
  public java.util.List<NagPolicyRepository.StoredPolicy> overrides() {
    return policies.findAllUserOverrides();
  }

  /** 覆盖行只接管阈值与渠道字段，调度字段回落到全局值。 */
  private EffectiveNagPolicy merge(EffectiveNagPolicy global, EffectiveNagPolicy override) {
    return new EffectiveNagPolicy(
        global.scanIntervalMinutes(),
        global.presenceGraceSeconds(),
        global.heartbeatIntervalSeconds(),
        override.firstThresholdMinutes(),
        override.repeatIntervalMinutes(),
        override.dailyMax(),
        override.fullscreenTimeoutMinutes(),
        override.quietStart(),
        override.quietEnd(),
        override.minPending(),
        override.minReasonLength(),
        override.channelFullscreenEnabled(),
        override.channelServerchanEnabled(),
        override.messageTemplate() == null || override.messageTemplate().isBlank()
            ? global.messageTemplate()
            : override.messageTemplate(),
        override.notifySupervisorOnBulkDelete(),
        override.notifySupervisorOnGaveUp(),
        override.notifySupervisorOnHalfDoneDelete());
  }
}
