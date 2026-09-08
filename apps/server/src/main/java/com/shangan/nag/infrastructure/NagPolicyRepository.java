package com.shangan.nag.infrastructure;

import com.shangan.nag.domain.EffectiveNagPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 催办策略持久化边界；GLOBAL 固定一行，USER 为可选覆盖。 */
public interface NagPolicyRepository {

  /** 全局默认策略；缺失时调用方使用内置默认值。 */
  Optional<StoredPolicy> findGlobal();

  Optional<StoredPolicy> findByUser(String userId);

  List<StoredPolicy> findAllUserOverrides();

  void saveGlobal(EffectiveNagPolicy policy, Instant now);

  void saveUserOverride(String userId, EffectiveNagPolicy policy, Instant now);

  void deleteUserOverride(String userId);

  /** 全局通知方式，不参与用户覆盖。 */
  Optional<com.shangan.nag.domain.NagTransportMode> findTransportMode();

  void saveTransportMode(com.shangan.nag.domain.NagTransportMode mode, Instant now);

  /** 数据库中的策略行，含所属范围。 */
  record StoredPolicy(String id, String scope, String userId, EffectiveNagPolicy policy) {}
}
