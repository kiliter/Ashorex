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
 * <p>在线走全屏；离线及全屏超时走个人外部渠道。网络调用与投递结果写事务分离， 在同一个催办上最多成功追加一次外部投递（见 ADR-0026）。
 */
@Service
public class NagDeliveryService {

  private final NagRepository nags;
  private final List<NagChannel> channels;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final org.springframework.transaction.support.TransactionTemplate transaction;
  private final com.shangan.identity.application.UserTimeService userTime;

  public NagDeliveryService(
      NagRepository nags,
      List<NagChannel> channels,
      IdGenerator idGenerator,
      Clock clock,
      com.shangan.identity.application.UserTimeService userTime,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
    this.nags = nags;
    this.channels = channels;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.userTime = userTime;
    this.transaction =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  }

  /** 首次投递：按在线状态选择渠道。 */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
  public void deliver(
      Nag nag,
      String recipientDisplayName,
      PresenceSnapshot presence,
      EffectiveNagPolicy policy,
      NagChannelType preferred) {
    Instant now = clock.instant();
    NagChannelType target =
        preferred != null ? preferred : chooseChannel(nag.userId(), presence, policy, now);
    if (target != null) dispatch(nag, recipientDisplayName, target);
  }

  /** 降级扫描：单实例内串行检查和投递，避免并发扫描重复发送。 */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
  public synchronized int escalateTimedOut(
      java.util.function.Function<String, EffectiveNagPolicy> policies,
      java.util.function.Function<String, String> displayNames) {
    Instant now = clock.instant();
    int escalated = 0;
    for (Nag candidate : nags.findFullscreenAwaitingBefore(now)) {
      // 单实例内串行降级；进入发送前重读，排除已回应或已过期的旧快照。
      Nag nag = nags.findById(candidate.id()).orElse(null);
      if (nag == null || nag.status() != com.shangan.nag.domain.NagStatus.DELIVERED) continue;
      // 用户覆盖可以缩短/延长超时或关闭推送，不能使用全局值提前投递。
      EffectiveNagPolicy policy = policies.apply(nag.userId());
      if (nag.deliveredAt() == null
          || !nag.deliveredAt().isBefore(now.minus(policy.fullscreenTimeout()))) continue;
      if (nags.deliveredVia(nag.id(), NagChannelType.SERVERCHAN)
          || nags.deliveredVia(nag.id(), NagChannelType.BARK)) {
        continue;
      }
      if (!externalEnabled(nag.userId(), policy)) {
        continue;
      }
      // 全屏等待期间可能跨入免打扰时段，降级同样遵守学员当前本地时间。
      if (policy.inQuietHours(userTime.localTimeNow(userTime.requireUser(nag.userId())))) continue;
      dispatch(nag, displayNames.apply(nag.userId()), externalChannel(nag.userId()));
      escalated++;
    }
    return escalated;
  }

  private void dispatch(Nag nag, String recipientDisplayName, NagChannelType target) {
    Instant now = clock.instant();
    Optional<NagChannel> channel =
        channels.stream().filter(candidate -> candidate.type() == target).findFirst();
    if (channel.isEmpty() || !channel.get().available(nag.userId())) {
      // 失败原因交由渠道自述，后台才能区分「没填 SendKey」与「推送被关」这类不同处置动作。
      String reason = channel.map(NagChannel::unavailableReason).orElse("渠道未注册");
      recordOutcome(nag, target, NagChannel.DeliveryOutcome.failed(reason), now);
      return;
    }
    if (target == NagChannelType.FULLSCREEN) {
      // 本地渠道只发布事务事件；与流水一起提交，SSE 的 AFTER_COMMIT 监听器才能收到通知。
      transaction.executeWithoutResult(
          status -> {
            if (nags.findById(nag.id()).isEmpty()) return;
            var outcome = channel.get().deliver(nag, recipientDisplayName);
            persistOutcome(nag, target, outcome, clock.instant());
          });
      return;
    }
    NagChannel.DeliveryOutcome outcome = channel.get().deliver(nag, recipientDisplayName);
    recordOutcome(nag, target, outcome, clock.instant());
  }

  /** 外发结束后仅把流水和状态更新放入短事务，网络等待期间不持有 SQLite 写锁。 */
  private void recordOutcome(
      Nag nag, NagChannelType target, NagChannel.DeliveryOutcome outcome, Instant now) {
    transaction.executeWithoutResult(
        status -> {
          // 外发期间管理员可能已级联删除学员或催办，不再插入失去父记录的流水。
          if (nags.findById(nag.id()).isPresent()) persistOutcome(nag, target, outcome, now);
        });
  }

  /** 调用方负责短事务，流水与状态一起提交；已回应状态不会被仓储回退。 */
  private void persistOutcome(
      Nag nag, NagChannelType target, NagChannel.DeliveryOutcome outcome, Instant now) {
    nags.insertDelivery(
        idGenerator.nextId(), nag.id(), target, outcome.status(), outcome.detail(), now);
    if (outcome.succeeded()) nags.markDelivered(nag.id(), now);
  }

  /** Bark 开启即替代 Server 酱，失败也不双发。 */
  private NagChannelType externalChannel(String userId) {
    return channels.stream().anyMatch(c -> c.type() == NagChannelType.BARK && c.enabled(userId))
        ? NagChannelType.BARK
        : NagChannelType.SERVERCHAN;
  }

  /** Bark 是独立开关；旧的 Server 酱开关仅控制原渠道。 */
  public boolean externalEnabled(String userId, EffectiveNagPolicy policy) {
    return externalChannel(userId) == NagChannelType.BARK || policy.channelServerchanEnabled();
  }

  /** 在线优先全屏；不在线或全屏被关闭时使用个人外部渠道。 */
  private NagChannelType chooseChannel(
      String userId, PresenceSnapshot presence, EffectiveNagPolicy policy, Instant now) {
    boolean online = presence != null && presence.online(now, policy.presenceGrace());
    if (online && policy.channelFullscreenEnabled()) {
      return NagChannelType.FULLSCREEN;
    }
    if (externalEnabled(userId, policy)) {
      return externalChannel(userId);
    }
    // 两个渠道都关闭时不暗中回退到全屏。
    return policy.channelFullscreenEnabled() ? NagChannelType.FULLSCREEN : null;
  }
}
