package com.shangan.presence.application;

import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.nag.application.NagResponseService;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.presence.domain.PresenceSnapshot;
import com.shangan.presence.domain.PresenceState;
import com.shangan.presence.infrastructure.UserPresenceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 心跳处理与在线判定。
 *
 * <p>心跳只更新最近心跳时间与前后台状态，**不刷新有效操作时间**；响应向客户端下发实际心跳间隔 与待回应催办 ID（见 ADR-0026）。
 */
@Service
public class PresenceService {

  private final UserPresenceRepository presence;
  private final NagPolicyResolver policies;
  private final NagResponseService nagResponses;
  private final UserTimeService userTime;
  private final Clock clock;
  private final com.shangan.nag.application.NagTransportService transport;

  public PresenceService(
      UserPresenceRepository presence,
      NagPolicyResolver policies,
      NagResponseService nagResponses,
      UserTimeService userTime,
      Clock clock,
      com.shangan.nag.application.NagTransportService transport) {
    this.presence = presence;
    this.policies = policies;
    this.nagResponses = nagResponses;
    this.userTime = userTime;
    this.clock = clock;
    this.transport = transport;
  }

  @Transactional
  public HeartbeatResponse heartbeat(String userId, String appState, String clientVersion) {
    Instant now = clock.instant();
    String normalizedState = "FOREGROUND".equalsIgnoreCase(appState) ? "FOREGROUND" : "BACKGROUND";
    presence.recordHeartbeat(userId, now, normalizedState, clientVersion);
    EffectiveNagPolicy policy = policies.resolve(userId);
    Optional<Nag> pending = nagResponses.pending(userId);
    return new HeartbeatResponse(
        now,
        policy.heartbeatIntervalSeconds(),
        pending.map(Nag::id).orElse(null),
        pending.map(Nag::message).orElse(null),
        pending.map(Nag::requireReason).orElse(false),
        policy.minReasonLength(),
        transport.current());
  }

  /** 某用户当前在线状态与空闲时长，供后台与督学端展示。 */
  @Transactional(readOnly = true)
  public PresenceView view(User user) {
    EffectiveNagPolicy policy = policies.resolve(user.id());
    PresenceSnapshot snapshot =
        presence.find(user.id()).orElseGet(() -> PresenceSnapshot.empty(user.id()));
    Instant now = clock.instant();
    Duration idleThreshold = Duration.ofMinutes(policy.firstThresholdMinutes());
    PresenceState state = snapshot.state(now, policy.presenceGrace(), idleThreshold);
    long idleMinutes = snapshot.idleMinutes(now);
    return new PresenceView(
        user.id(),
        state,
        snapshot.lastHeartbeatAt(),
        snapshot.lastEffectiveActionAt(),
        idleMinutes > 60 * 24 * 365 ? -1 : idleMinutes,
        snapshot.appState(),
        snapshot.clientVersion(),
        userTime.today(user).toString());
  }

  /** 心跳响应：客户端据此调整上报间隔并决定是否拉起全屏催办。 */
  public record HeartbeatResponse(
      Instant serverTime,
      int heartbeatIntervalSeconds,
      String pendingNagId,
      String pendingNagMessage,
      boolean requireReason,
      int minReasonLength,
      com.shangan.nag.domain.NagTransportMode nagTransportMode) {}

  /** 在线状态视图；{@code idleMinutes} 为 -1 表示从未有过有效操作。 */
  public record PresenceView(
      String userId,
      PresenceState state,
      Instant lastHeartbeatAt,
      Instant lastEffectiveActionAt,
      long idleMinutes,
      String appState,
      String clientVersion,
      String localDate) {}
}
