package com.shangan.nag.application;

import com.shangan.common.IdGenerator;
import com.shangan.nag.application.channel.NagChannel;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.domain.PresenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 催办投递与渠道降级。
 *
 * <p>在线走全屏；离线走 Server 酱；全屏投递后超过 {@code fullscreenTimeoutMinutes} 仍未回应， 在同一个催办上追加一条 Server 酱投递（见
 * ADR-0026）。
 */
@Service
public class NagDeliveryService {

  private final NagRepository nags;
  private final List<NagChannel> channels;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final com.shangan.identity.application.UserTimeService userTime;

  public NagDeliveryService(
      NagRepository nags,
      List<NagChannel> channels,
      IdGenerator idGenerator,
      Clock clock,
      com.shangan.identity.application.UserTimeService userTime) {
    this.nags = nags;
    this.channels = channels;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.userTime = userTime;
  }

  /** 首次投递：按在线状态选择渠道。 */
  @Transactional
  public void deliver(
      Nag nag,
      String recipientDisplayName,
      PresenceSnapshot presence,
      EffectiveNagPolicy policy,
      NagChannelType preferred) {
    Instant now = clock.instant();
    NagChannelType target = preferred != null ? preferred : chooseChannel(presence, policy, now);
    if (target != null) dispatch(nag, recipientDisplayName, target);
  }

  /** 降级扫描：把超时未回应的全屏催办追加一次 Server 酱投递。 */
  @Transactional
  public int escalateTimedOut(
      java.util.function.Function<String, EffectiveNagPolicy> policies,
      java.util.function.Function<String, String> displayNames) {
    Instant now = clock.instant();
    int escalated = 0;
    for (Nag nag : nags.findFullscreenAwaitingBefore(now)) {
      // 用户覆盖可以缩短/延长超时或关闭推送，不能使用全局值提前投递。
      EffectiveNagPolicy policy = policies.apply(nag.userId());
      if (nag.deliveredAt() == null
          || !nag.deliveredAt().isBefore(now.minus(policy.fullscreenTimeout()))) continue;
      if (nags.deliveredVia(nag.id(), NagChannelType.SERVERCHAN)) {
        continue;
      }
      if (!policy.channelServerchanEnabled()) {
        continue;
      }
      // 全屏等待期间可能跨入免打扰时段，降级同样遵守学员当前本地时间。
      if (policy.inQuietHours(userTime.localTimeNow(userTime.requireUser(nag.userId())))) continue;
      dispatch(nag, displayNames.apply(nag.userId()), NagChannelType.SERVERCHAN);
      escalated++;
    }
    return escalated;
  }

  private void dispatch(Nag nag, String recipientDisplayName, NagChannelType target) {
    Instant now = clock.instant();
    Optional<NagChannel> channel =
        channels.stream().filter(candidate -> candidate.type() == target).findFirst();
    if (channel.isEmpty() || !channel.get().available()) {
      // 失败原因交由渠道自述，后台才能区分「没填 SendKey」与「推送被关」这类不同处置动作。
      String reason = channel.map(NagChannel::unavailableReason).orElse("渠道未注册");
      nags.insertDelivery(idGenerator.nextId(), nag.id(), target, "FAILED", reason, now);
      return;
    }
    NagChannel.DeliveryOutcome outcome = channel.get().deliver(nag, recipientDisplayName);
    nags.insertDelivery(
        idGenerator.nextId(), nag.id(), target, outcome.status(), outcome.detail(), now);
    if (outcome.succeeded()) {
      nags.markDelivered(nag.id(), now);
    }
  }

  /** 在线优先全屏；不在线或全屏被关闭时用 Server 酱。 */
  private NagChannelType chooseChannel(
      PresenceSnapshot presence, EffectiveNagPolicy policy, Instant now) {
    boolean online = presence != null && presence.online(now, policy.presenceGrace());
    if (online && policy.channelFullscreenEnabled()) {
      return NagChannelType.FULLSCREEN;
    }
    if (policy.channelServerchanEnabled()) {
      return NagChannelType.SERVERCHAN;
    }
    // 两个渠道都关闭时不暗中回退到全屏。
    return policy.channelFullscreenEnabled() ? NagChannelType.FULLSCREEN : null;
  }
}
