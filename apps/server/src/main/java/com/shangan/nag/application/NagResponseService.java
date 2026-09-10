package com.shangan.nag.application;

import com.shangan.common.api.BusinessException;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.application.EffectiveActionRecorder;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 学员侧催办读取与回应；回应必须填原因，并刷新有效操作时间。 */
@Service
public class NagResponseService {

  private static final Logger log = LoggerFactory.getLogger(NagResponseService.class);

  private final NagRepository nags;
  private final NagPolicyResolver policies;
  private final EffectiveActionRecorder effectiveAction;
  private final Clock clock;
  private final com.shangan.identity.application.UserTimeService userTime;

  public NagResponseService(
      NagRepository nags,
      NagPolicyResolver policies,
      EffectiveActionRecorder effectiveAction,
      Clock clock,
      com.shangan.identity.application.UserTimeService userTime) {
    this.nags = nags;
    this.policies = policies;
    this.effectiveAction = effectiveAction;
    this.clock = clock;
    this.userTime = userTime;
  }

  /**
   * 心跳只读今日待回应催办，不在每 60 秒的写事务里抢 {@code nags} 表锁。
   *
   * <p>跨日记录由扫描任务或 {@link #pending(String)} 清理；这里按学员日期过滤，避免旧全屏提醒被再次下发。
   */
  public Optional<Nag> peekPending(String userId) {
    return awaitingToday(userId);
  }

  /** 当前待回应催办；客户端打开全屏催办前拉取，并尽量落库跨日过期。 */
  @Transactional
  public Optional<Nag> pending(String userId) {
    LocalDate today = userTime.today(userId);
    try {
      // 回到 App 即清理跨日催办，不等待下一轮扫描后才解除旧全屏提醒。
      nags.expireBefore(userId, today);
      nags.supersedeOlderAppNags(userId);
    } catch (RuntimeException ex) {
      if (!isSqliteBusy(ex)) {
        throw ex;
      }
      // 双端同时拉催办时允许跳过本次清理，读路径仍按今日过滤，避免 500 打断全屏流程。
      log.warn("清理跨日催办时数据库忙，改为只返回今日待回应催办");
    }
    return awaitingToday(userId);
  }

  /** 只返回学员今日仍待回应的催办；昨日记录即使尚未写成 EXPIRED 也不再弹出。 */
  private Optional<Nag> awaitingToday(String userId) {
    LocalDate today = userTime.today(userId);
    return nags.findAwaitingByUser(userId)
        .filter(nag -> !nag.localDate().isBefore(today));
  }

  /** SQLite 写冲突在 JDBC 中常表现为 error code 5，busy_timeout 未等待时也会立刻抛出。 */
  private static boolean isSqliteBusy(RuntimeException ex) {
    for (Throwable current = ex; current != null; current = current.getCause()) {
      if (current instanceof SQLException sql && sql.getErrorCode() == 5) {
        return true;
      }
      String message = current.getMessage();
      if (message != null
          && (message.contains("SQLITE_BUSY") || message.contains("database is locked"))) {
        return true;
      }
    }
    return false;
  }

  @Transactional(readOnly = true)
  public List<Nag> history(String userId, int limit) {
    return nags.findByUser(userId, limit);
  }

  /** 回应催办；原因标签与说明都必填，长度下限来自生效策略。 */
  @Transactional
  public Nag respond(String userId, String nagId, String reasonTag, String reasonText) {
    Nag nag =
        nags.findById(nagId)
            .filter(candidate -> candidate.userId().equals(userId))
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "NAG_NOT_FOUND", "催办不存在"));
    // 请求重放保留首次回应；跨日旧弹框不能重新激活已结束的催办。
    if (nag.status() == com.shangan.nag.domain.NagStatus.RESPONDED) return nag;
    if (nag.status() == com.shangan.nag.domain.NagStatus.EXPIRED
        || nag.localDate().isBefore(userTime.today(userId))) {
      throw new BusinessException(HttpStatus.CONFLICT, "NAG_EXPIRED", "该催办已过期，请刷新");
    }
    if (nag.status() == com.shangan.nag.domain.NagStatus.CANCELLED)
      throw new BusinessException(HttpStatus.CONFLICT, "NAG_CANCELLED", "该催办已取消，请刷新");
    // 旧版 App 只识别成功后关页：返回旧记录的真实状态，不伪造回应或刷新有效操作。
    // 外层会再次查询最新项；不引入旧版无法处理的错误码而将用户锁在全屏页。
    if (nag.appSuperseded()) return nag;
    EffectiveNagPolicy policy = policies.resolve(userId);
    if (nag.requireReason()) {
      if (reasonTag == null || reasonTag.isBlank()) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "NAG_REASON_REQUIRED", "必须选择一个原因");
      }
      String text = reasonText == null ? "" : reasonText.trim();
      if (text.length() < policy.minReasonLength()) {
        throw new BusinessException(
            HttpStatus.BAD_REQUEST,
            "NAG_REASON_REQUIRED",
            "原因说明至少需要 " + policy.minReasonLength() + " 个字");
      }
    }
    nags.markResponded(
        nag.id(), reasonTag, reasonText == null ? "" : reasonText.trim(), clock.instant());
    effectiveAction.record(userId);
    return nags.findById(nag.id()).orElse(nag);
  }
}
