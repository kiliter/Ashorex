package com.shangan.nag.application;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.nag.domain.*;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.infrastructure.UserPresenceRepository;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 失败投递的后台闭环；单实例互斥与最近尝试 ID 共同阻止并发及旧页面重复发送。 */
@Service
public class NagManagementService {
  private final NagRepository nags;
  private final NagDeliveryService delivery;
  private final NagPolicyResolver policies;
  private final UserTimeService userTime;
  private final UserPresenceRepository presence;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final Set<String> active = ConcurrentHashMap.newKeySet();

  public NagManagementService(
      NagRepository nags,
      NagDeliveryService delivery,
      NagPolicyResolver policies,
      UserTimeService userTime,
      UserPresenceRepository presence,
      Clock clock,
      PlatformTransactionManager manager) {
    this.nags = nags;
    this.delivery = delivery;
    this.policies = policies;
    this.userTime = userTime;
    this.presence = presence;
    this.clock = clock;
    this.transaction = new TransactionTemplate(manager);
  }

  /** 取消和留痕在同一短事务提交，操作期间不允许重投。 */
  @Transactional(propagation = Propagation.NEVER)
  public void cancel(String id, String latestAttemptId, String actor) {
    acquire(id);
    try {
      transaction.executeWithoutResult(
          status -> {
            requireFailed(id, latestAttemptId);
            if (!nags.cancelFailed(id)) throw changed();
            nags.recordAdminAction(id, "CANCEL", actor, clock.instant());
          });
    } finally {
      active.remove(id);
    }
  }

  /** 读取当前策略后复用原催办投递，网络等待在写事务之外。 */
  @Transactional(propagation = Propagation.NEVER)
  public void retry(String id, String latestAttemptId, String actor) {
    acquire(id);
    try {
      Nag nag = requireFailed(id, latestAttemptId);
      var user = userTime.requireUser(nag.userId());
      if (!user.active()) throw changed();
      if (nag.localDate().isBefore(userTime.today(nag.userId())))
        throw new BusinessException(HttpStatus.CONFLICT, "NAG_EXPIRED", "该催办已过期，请刷新");
      var policy = policies.resolve(nag.userId());
      // 自动催办仍遵守免打扰；手动催办沿用渠道自身的通知级别规则。
      if (nag.trigger() == NagTrigger.AUTO && policy.inQuietHours(userTime.localTimeNow(user)))
        throw new BusinessException(HttpStatus.CONFLICT, "NAG_QUIET_HOURS", "当前为免打扰时段，请稍后重试");
      if (!policy.channelFullscreenEnabled() && !delivery.externalEnabled(nag.userId(), policy))
        throw new BusinessException(HttpStatus.CONFLICT, "NAG_CHANNEL_DISABLED", "请先启用投递渠道");
      transaction.executeWithoutResult(
          status -> {
            requireFailed(id, latestAttemptId);
            nags.recordAdminAction(id, "RETRY", actor, clock.instant());
          });
      delivery.deliver(
          nag, user.displayName(), presence.find(nag.userId()).orElse(null), policy, null);
    } finally {
      active.remove(id);
    }
  }

  /** 以页面看到的最后一次流水作为乐观版本，失败重投完成后旧请求也不能再次执行。 */
  private Nag requireFailed(String id, String latestAttemptId) {
    Nag nag =
        nags.findById(id)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "NAG_NOT_FOUND", "催办不存在"));
    var attempts = nags.deliveriesOf(id);
    if (nag.status() != NagStatus.PENDING
        || attempts.isEmpty()
        || attempts.stream().anyMatch(item -> !"FAILED".equals(item.status()))
        || !attempts.getLast().id().equals(latestAttemptId)) throw changed();
    return nag;
  }

  /** 不排队等待外部请求，第二次点击立即提示刷新。 */
  private void acquire(String id) {
    if (!active.add(id))
      throw new BusinessException(
          HttpStatus.CONFLICT, "NAG_OPERATION_IN_PROGRESS", "该催办正在处理，请稍后刷新");
  }

  private BusinessException changed() {
    return new BusinessException(HttpStatus.CONFLICT, "NAG_STATE_CHANGED", "催办状态已变化，请刷新后操作");
  }
}
