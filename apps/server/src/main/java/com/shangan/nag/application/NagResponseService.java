package com.shangan.nag.application;

import com.shangan.common.api.BusinessException;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.application.EffectiveActionRecorder;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 学员侧催办读取与回应；回应必须填原因，并刷新有效操作时间。 */
@Service
public class NagResponseService {

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

  /** 当前待回应催办；客户端在心跳响应或首页进入时拉取。 */
  @Transactional
  public Optional<Nag> pending(String userId) {
    // 回到 App 即清理跨日催办，不等待下一轮扫描后才解除旧全屏提醒。
    nags.expireBefore(userId, userTime.today(userId));
    return nags.findAwaitingByUser(userId);
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
