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
      Clock clock) {
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
  }

  /** 执行一轮扫描；返回本轮生成与降级的数量，便于后台展示与日志。 */
  @Transactional
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
  @Transactional
  public Optional<Nag> createAutoNag(User user) {
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
    delivery.deliver(nag, user.displayName(), snapshot, policy, null);
    return Optional.of(nag);
  }

  /** 管理员手动催办或督学人一键督学；不参与自动幂等键，但计入每日上限。 */
  @Transactional
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
  @Transactional
  public Nag createManualNag(
      String learnerUserId,
      String triggeredByUserId,
      NagTrigger trigger,
      String message,
      NagChannelType preferredChannel,
      boolean requireReason,
      String title) {
    title = Nag.manualText(title, 80);
    message = Nag.manualText(message, 1000);
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
    delivery.deliver(nag, user.displayName(), snapshot, policy, preferredChannel);
    return nag;
  }

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
