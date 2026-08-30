package com.shangan.learning.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.debt.application.DebtService;
import com.shangan.identity.infrastructure.UserRepository;
import com.shangan.learning.api.WatchHeartbeatRequest;
import com.shangan.learning.api.WatchHeartbeatResponse;
import com.shangan.learning.domain.WatchProgressPolicy;
import com.shangan.learning.infrastructure.VideoProgressRepository;
import com.shangan.learning.infrastructure.WatchSessionRepository;
import com.shangan.planning.application.PlanProgressPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 可信观看事务边界：校验心跳、同步绝对进度、触发验活并对账计划与欠债。 */
@Service
public class WatchSessionService {
  private static final Duration ALIVE_CHECK_TIMEOUT = Duration.ofSeconds(60);

  private final WatchSessionRepository sessions;
  private final VideoProgressRepository progress;
  private final PlanProgressPort plans;
  private final DebtService debts;
  private final UserRepository users;
  private final AliveCheckScheduler aliveChecks;
  private final WatchProgressPolicy policy;
  private final IdGenerator ids;
  private final Clock clock;

  public WatchSessionService(
      WatchSessionRepository sessions,
      VideoProgressRepository progress,
      @Lazy PlanProgressPort plans,
      DebtService debts,
      UserRepository users,
      AliveCheckScheduler aliveChecks,
      IdGenerator ids,
      Clock clock) {
    this.sessions = sessions;
    this.progress = progress;
    this.plans = plans;
    this.debts = debts;
    this.users = users;
    this.aliveChecks = aliveChecks;
    this.policy = new WatchProgressPolicy();
    this.ids = ids;
    this.clock = clock;
  }

  /** 单条短事务处理心跳；重复序号直接返回当前聚合值，不重复累计。 */
  @Transactional
  public WatchHeartbeatResponse heartbeat(
      String userId, String sessionId, WatchHeartbeatRequest request) {
    Instant now = clock.instant();
    WatchSessionRepository.Session session = requireOwned(userId, sessionId);
    requireOpen(session);
    failTimedOutAliveCheck(session, now);

    WatchProgressPolicy.Decision decision =
        policy.evaluate(
            new WatchProgressPolicy.State(
                session.lastReportedPositionMs(),
                session.maxVerifiedPositionMs(),
                session.verifiedWatchMs(),
                session.lastSequence(),
                session.lastHeartbeatAt(),
                session.aliveCheckPending()),
            new WatchProgressPolicy.Heartbeat(
                request.sequence(), request.positionMs(), request.playing(), request.foreground()),
            now);

    boolean completed = policy.completed(decision.maxVerifiedPositionMs(), session.durationMs());
    boolean pending = session.aliveCheckPending() && !completed;
    String status = completed ? "COMPLETED" : pending ? "PAUSED" : session.status();
    boolean requireNewAliveCheck =
        !pending
            && !completed
            && session.aliveCheckDueWatchMs() != null
            && decision.verifiedWatchMs() >= session.aliveCheckDueWatchMs();
    if (requireNewAliveCheck) {
      pending = true;
      status = "PAUSED";
    }
    if (!decision.duplicate()) {
      boolean updated = sessions.updateHeartbeat(session.id(), decision, pending, status, now);
      if (!updated) return duplicateResponse(requireOwned(userId, sessionId));
      if (requireNewAliveCheck) sessions.insertAliveCheck(ids.nextId(), session.id(), now);
    }
    SyncedProgress synced =
        synchronize(session, decision.maxVerifiedPositionMs(), decision.verifiedWatchMs(), now);
    return new WatchHeartbeatResponse(
        decision.trustedPositionMs(),
        decision.verifiedWatchMs(),
        decision.seekAllowed(),
        pending,
        synced.completed(),
        status);
  }

  /** 并发相同序号只有先完成者能写入，后到请求读取并返回已保存的聚合值。 */
  private WatchHeartbeatResponse duplicateResponse(WatchSessionRepository.Session session) {
    return new WatchHeartbeatResponse(
        session.maxVerifiedPositionMs(),
        session.verifiedWatchMs(),
        true,
        session.aliveCheckPending(),
        policy.completed(session.maxVerifiedPositionMs(), session.durationMs()),
        session.status());
  }

  /** 确认当前验活；超时确认保留 FAILED 记录，但仍允许继续学习并生成下一阈值。 */
  @Transactional
  public WatchHeartbeatResponse confirmAliveCheck(String userId, String sessionId) {
    Instant now = clock.instant();
    WatchSessionRepository.Session session = requireOwned(userId, sessionId);
    requireOpen(session);
    if (!session.aliveCheckPending()) {
      throw new BusinessException(HttpStatus.CONFLICT, "ALIVE_CHECK_NOT_PENDING", "当前没有待确认的验活");
    }
    sessions
        .findUnansweredAliveCheck(session.id())
        .ifPresent(
            check -> {
              boolean timedOut = now.isAfter(check.requiredAt().plus(ALIVE_CHECK_TIMEOUT));
              sessions.answerAliveCheck(check.id(), timedOut ? "FAILED" : "PASSED", now);
            });
    String level = users.findById(userId).orElseThrow(() -> notFound("用户不存在")).aliveCheckLevel();
    Long nextDue =
        aliveChecks.nextDueWatchMs(level, session.verifiedWatchMs()).stream()
            .boxed()
            .findFirst()
            .orElse(null);
    sessions.setAliveState(session.id(), false, nextDue, "ACTIVE", now);
    boolean completed = policy.completed(session.maxVerifiedPositionMs(), session.durationMs());
    return new WatchHeartbeatResponse(
        session.maxVerifiedPositionMs(),
        session.verifiedWatchMs(),
        true,
        false,
        completed,
        "ACTIVE");
  }

  @Transactional
  public WatchHeartbeatResponse stop(String userId, String sessionId) {
    return stopAt(userId, sessionId, clock.instant());
  }

  /** 供日终/开摆关闭器调用，先同步最后可信聚合值再落最终状态。 */
  @Transactional
  public WatchHeartbeatResponse stopAt(String userId, String sessionId, Instant stoppedAt) {
    WatchSessionRepository.Session session = requireOwned(userId, sessionId);
    SyncedProgress synced =
        synchronize(session, session.maxVerifiedPositionMs(), session.verifiedWatchMs(), stoppedAt);
    if (session.open()) sessions.stop(session.id(), "STOPPED", stoppedAt);
    return new WatchHeartbeatResponse(
        synced.maximumMs(), session.verifiedWatchMs(), true, false, synced.completed(), "STOPPED");
  }

  private SyncedProgress synchronize(
      WatchSessionRepository.Session session,
      long sessionMaximumMs,
      long sessionVerifiedWatchMs,
      Instant now) {
    long existingMaximum =
        progress
            .find(session.userId(), session.mediaItemId())
            .map(VideoProgressRepository.Progress::maxVerifiedPositionMs)
            .orElse(0L);
    long absoluteMaximum = Math.max(existingMaximum, sessionMaximumMs);
    boolean completed = policy.completed(absoluteMaximum, session.durationMs());
    long watchDelta = Math.max(0, sessionVerifiedWatchMs - session.syncedVerifiedWatchMs());
    VideoProgressRepository.Progress synced =
        progress.synchronize(
            ids.nextId(),
            session.userId(),
            session.mediaItemId(),
            absoluteMaximum,
            watchDelta,
            completed,
            now);
    if (watchDelta > 0) sessions.markSynced(session.id(), sessionVerifiedWatchMs, now);
    plans.updateVideoWatchProgress(
        session.userId(), session.planItemId(), synced.maxVerifiedPositionMs() / 1000, completed);
    debts.reconcileOpenVideoDebt(
        session.userId(),
        session.mediaItemId(),
        synced.maxVerifiedPositionMs() / 1000,
        completed,
        now);
    return new SyncedProgress(synced.maxVerifiedPositionMs(), completed);
  }

  private void failTimedOutAliveCheck(WatchSessionRepository.Session session, Instant now) {
    if (!session.aliveCheckPending()) return;
    sessions
        .findUnansweredAliveCheck(session.id())
        .filter(check -> now.isAfter(check.requiredAt().plus(ALIVE_CHECK_TIMEOUT)))
        .ifPresent(check -> sessions.answerAliveCheck(check.id(), "FAILED", now));
  }

  private WatchSessionRepository.Session requireOwned(String userId, String sessionId) {
    return sessions.findOwned(userId, sessionId).orElseThrow(() -> notFound("观看会话不存在"));
  }

  private void requireOpen(WatchSessionRepository.Session session) {
    if (!session.open()) {
      throw new BusinessException(HttpStatus.CONFLICT, "WATCH_SESSION_CLOSED", "观看会话已经结束");
    }
  }

  private BusinessException notFound(String message) {
    return new BusinessException(HttpStatus.NOT_FOUND, "WATCH_SESSION_NOT_FOUND", message);
  }

  private record SyncedProgress(long maximumMs, boolean completed) {}
}
