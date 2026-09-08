package com.shangan.presence.infrastructure;

import com.shangan.presence.domain.PresenceSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 在线状态持久化边界；只维护当前态单行，不保存心跳明细历史（见 ADR-0026）。 */
public interface UserPresenceRepository {

  Optional<PresenceSnapshot> find(String userId);

  List<PresenceSnapshot> findAll();

  /** 心跳只更新最近心跳时间与前后台状态。 */
  void recordHeartbeat(
      String userId,
      Instant at,
      String appState,
      String clientVersion,
      com.shangan.presence.domain.AppActivity activity);

  /** 有效操作单独更新，用于空闲判定。 */
  void recordEffectiveAction(String userId, Instant at);
}
