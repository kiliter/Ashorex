package com.shangan.nag.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.domain.PresenceSnapshot;
import com.shangan.presence.infrastructure.UserPresenceRepository;
import com.shangan.supervision.application.SupervisionService;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 催办扫描与创建。
 *
 * <p>扫描条件（Spec 第 8 章）：当日仍有未完成 Todo、空闲时长达到阈值、不在免打扰时段、未超每日上限。 自动催办按 {@code (user, local_date, level)}
 * 幂等。
 */
@Service
public class NagScanner {

  private static final Logger log = LoggerFactory.getLogger(NagScanner.class);

  private final AuthService users;
  private final UserTimeService userTime;
  private final TodoRepository todos;
  private final UserPresenceRepository presence;
  private final NagRepository nags;
  private final NagPolicyResolver policies;
  private final NagDeliveryService delivery;
  private final SupervisionService supervisions;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final org.springframework.transaction.support.TransactionTemplate transaction;
  private final Object creationLock = new Object();

  public NagScanner(
      AuthService users,
      UserTimeService userTime,
      TodoRepository todos,
      UserPresenceRepository presence,
      NagRepository nags,
      NagPolicyResolver policies,
      NagDeliveryService delivery,
      SupervisionService supervisions,
      IdGenerator idGenerator,
      Clock clock,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
    this.users = users;
    this.userTime = userTime;
    this.todos = todos;
    this.presence = presence;
    this.nags = nags;
    this.policies = policies;
    this.delivery = delivery;
    this.supervisions = supervisions;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.transaction =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  }

  /** 执行一轮扫描；返回本轮生成与降级的数量，便于后台展示与日志。 */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
  public ScanReport scanOnce() {
    int created = 0;
    int skipped = 0;
    for (User user : users.listActiveUsers()) {
      if (createAutoNag(user).isPresent()) {
        created++;
      } else {
        skipped++;
      }
    }
    int escalated =
        delivery.escalateTimedOut(
            policies::resolve,
            userId ->
                users.listUsers().stream()
                    .filter(candidate -> candidate.id().equals(userId))
                    .map(User::displayName)
                    .findFirst()
                    .orElse("学员"));
    ScanReport report = new ScanReport(created, skipped, escalated, clock.instant());
    if (created > 0 || escalated > 0) {
      log.info("催办扫描完成：新建 {}，降级 {}", created, escalated);
    }
    return report;
  }

  /** 对单个用户做一次判定；满足条件时创建并投递催办。 */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
  public Optional<Nag> createAutoNag(User user) {
    Optional<PreparedDelivery> prepared;
    // 单实例内串行检查每日上限和自动幂等键；锁仅持有至创建事务提交。
    synchronized (creationLock) {
      prepared = transaction.execute(status -> prepareAutoNag(user));
    }
    prepared.ifPresent(this::deliverPrepared);
    return prepared.map(PreparedDelivery::nag);
  }

  /** 在短事务内决定并保存自动催办，不调用外部渠道。 */
  private Optional<PreparedDelivery> prepareAutoNag(User user) {
    EffectiveNagPolicy policy = policies.resolve(user.id());
    LocalDate today = userTime.today(user);
    nags.expireBefore(user.id(), today);
    int pending = todos.countPendingOn(user.id(), today);
    if (!policy.channelFullscreenEnabled() && !delivery.externalEnabled(user.id(), policy))
      return Optional.empty();
    if (pending < policy.minPending()) {
      return Optional.empty();
    }
    if (policy.inQuietHours(userTime.localTimeNow(user))) {
      return Optional.empty();
    }
    if (nags.countOn(user.id(), today) >= policy.dailyMax()) {
      return Optional.empty();
    }
    PresenceSnapshot snapshot =
        presence.find(user.id()).orElseGet(() -> PresenceSnapshot.empty(user.id()));
    long idleMinutes = snapshot.idleMinutes(clock.instant());
    Optional<Integer> level = policy.thresholdLevel(idleMinutes);
    if (level.isEmpty()) {
      return Optional.empty();
    }
    if (nags.autoNagExists(user.id(), today, level.get())) {
      return Optional.empty();
    }
    Nag nag =
        buildNag(
            user,
            today,
            level.get(),
            NagTrigger.AUTO,
            null,
            idleMinutes,
            pending,
            policy.renderMessage(user.displayName(), pending, idleMinutes, today.toString()),
            policy);
    nags.insert(nag);
    return Optional.of(new PreparedDelivery(nag, user.displayName(), snapshot, policy, null));
  }

  /** 管理员手动催办或督学人一键督学；不参与自动幂等键，但计入每日上限。 */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
  public Nag createManualNag(
      String learnerUserId,
      String triggeredByUserId,
      NagTrigger trigger,
      String message,
      NagChannelType preferredChannel,
      boolean requireReason) {
    return createManualNag(
        learnerUserId, triggeredByUserId, trigger, message, preferredChannel, requireReason, null);
  }

  /** 标题和附加说明仅扩展手动/督学入口，保留自动催办原内容。 */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
  public Nag createManualNag(
      String learnerUserId,
      String triggeredByUserId,
      NagTrigger trigger,
      String message,
      NagChannelType preferredChannel,
      boolean requireReason,
      String title) {
    String normalizedTitle = Nag.manualText(title, 80);
    String normalizedMessage = Nag.manualText(message, 1000);
    PreparedDelivery prepared;
    synchronized (creationLock) {
      prepared =
          transaction.execute(
              status ->
                  prepareManualNag(
                      learnerUserId,
                      triggeredByUserId,
                      trigger,
                      normalizedMessage,
                      preferredChannel,
                      requireReason,
                      normalizedTitle));
    }
    deliverPrepared(prepared);
    return prepared.nag();
  }

  /** 每日上限检查和写入在同一短事务内完成，提交之后才能外发。 */
  private PreparedDelivery prepareManualNag(
      String learnerUserId,
      String triggeredByUserId,
      NagTrigger trigger,
      String message,
      NagChannelType preferredChannel,
      boolean requireReason,
      String title) {
    User user = userTime.requireUser(learnerUserId);
    EffectiveNagPolicy policy = policies.resolve(user.id());
    LocalDate today = userTime.today(user);
    if (nags.countOn(user.id(), today) >= policy.dailyMax()) {
      throw new BusinessException(HttpStatus.CONFLICT, "NAG_DAILY_LIMIT_REACHED", "该学员今日催办次数已达上限");
    }
    PresenceSnapshot snapshot =
        presence.find(user.id()).orElseGet(() -> PresenceSnapshot.empty(user.id()));
    nags.expireBefore(user.id(), today);
    int pending = todos.countPendingOn(user.id(), today);
    long idleMinutes = snapshot.idleMinutes(clock.instant());
    String text =
        message == null || message.isBlank()
            ? policy.renderMessage(user.displayName(), pending, idleMinutes, today.toString())
            : message.trim();
    Nag nag =
        buildNagWithRequirement(
                user,
                today,
                0,
                trigger,
                triggeredByUserId,
                idleMinutes,
                pending,
                text,
                requireReason)
            .withTitle(title);
    nags.insert(nag);
    return new PreparedDelivery(nag, user.displayName(), snapshot, policy, preferredChannel);
  }

  /** 提交完成后的编排只传递已持久化催办，不携带数据库事务。 */
  private void deliverPrepared(PreparedDelivery prepared) {
    delivery.deliver(
        prepared.nag(),
        prepared.displayName(),
        prepared.presence(),
        prepared.policy(),
        prepared.preferred());
  }

  private record PreparedDelivery(
      Nag nag,
      String displayName,
      PresenceSnapshot presence,
      EffectiveNagPolicy policy,
      NagChannelType preferred) {}

  private Nag buildNag(
      User user,
      LocalDate today,
      int level,
      NagTrigger trigger,
      String triggeredByUserId,
      long idleMinutes,
      int pending,
      String message,
      EffectiveNagPolicy policy) {
    return buildNagWithRequirement(
        user, today, level, trigger, triggeredByUserId, idleMinutes, pending, message, true);
  }

  private Nag buildNagWithRequirement(
      User user,
      LocalDate today,
      int level,
      NagTrigger trigger,
      String triggeredByUserId,
      long idleMinutes,
      int pending,
      String message,
      boolean requireReason) {
    Instant now = clock.instant();
    return new Nag(
        idGenerator.nextId(),
        user.id(),
        today,
        Math.max(1, level),
        trigger,
        triggeredByUserId,
        idleMinutes,
        pending,
        message,
        requireReason,
        NagStatus.PENDING,
        null,
        null,
        null,
        null,
        supervisions.primarySupervisorOf(user.id()).orElse(null),
        now);
  }

  /** 一轮扫描的结果摘要。 */
  public record ScanReport(int created, int skipped, int escalated, Instant scannedAt) {}

  /** 便于后台展示的可用学员列表。 */
  @Transactional(readOnly = true)
  public List<User> scannableUsers() {
    return users.listActiveUsers();
  }
}
