package com.shangan.focus.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.focus.domain.FocusSession;
import com.shangan.focus.infrastructure.FocusSessionRepository;
import com.shangan.planning.application.PlanProgressPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 专注会话应用服务，所有状态变化和计划同步位于同一短事务内。 */
@Service
public class FocusSessionService {
  private final FocusSessionRepository sessions;
  private final PlanProgressPort plans;
  private final IdGenerator ids;
  private final Clock clock;

  public FocusSessionService(
      FocusSessionRepository sessions, @Lazy PlanProgressPort plans, IdGenerator ids, Clock clock) {
    this.sessions = sessions;
    this.plans = plans;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public FocusView start(String userId, StartCommand command) {
    if (sessions.findActive(userId).isPresent()) {
      throw invalid("FOCUS_SESSION_ACTIVE", "已有进行中的专注会话");
    }
    plans.validateFocusLink(userId, command.planItemId());
    Instant now = clock.instant();
    FocusSession session =
        FocusSession.start(
            ids.nextId(),
            userId,
            command.planItemId(),
            command.mediaItemId(),
            command.focusType(),
            command.plannedSeconds(),
            now);
    sessions.insert(session, now);
    return view(session, now);
  }

  @Transactional
  public FocusView pause(String userId, String sessionId) {
    Instant now = clock.instant();
    FocusSession session = requireOwned(userId, sessionId);
    session.pause(now);
    persistAndSync(session, now, false);
    return view(session, now);
  }

  @Transactional
  public FocusView resume(String userId, String sessionId) {
    Instant now = clock.instant();
    FocusSession session = requireOwned(userId, sessionId);
    session.resume(now);
    sessions.update(session, now);
    return view(session, now);
  }

  @Transactional
  public FocusView finish(String userId, String sessionId) {
    return finishAt(userId, sessionId, clock.instant());
  }

  @Transactional
  public FocusView cancel(String userId, String sessionId) {
    return cancelAt(userId, sessionId, clock.instant());
  }

  /** 供计划关闭器传入同一关闭时刻，确保欠债计算看到最终有效秒数。 */
  @Transactional
  public FocusView cancelAt(String userId, String sessionId, Instant closedAt) {
    FocusSession session = requireOwned(userId, sessionId);
    session.cancel(closedAt);
    persistAndSync(session, closedAt, false);
    return view(session, closedAt);
  }

  @Transactional(readOnly = true)
  public Optional<FocusView> active(String userId) {
    Instant now = clock.instant();
    return sessions.findActive(userId).map(session -> view(session, now));
  }

  @Transactional(readOnly = true)
  public java.util.List<FocusSession> activeForPlan(String userId, String planId) {
    return sessions.findActiveByPlan(userId, planId);
  }

  private FocusView finishAt(String userId, String sessionId, Instant now) {
    FocusSession session = requireOwned(userId, sessionId);
    session.finish(now);
    boolean completed = session.actualSeconds() >= session.plannedSeconds();
    persistAndSync(session, now, completed);
    return view(session, now);
  }

  private void persistAndSync(FocusSession session, Instant now, boolean completed) {
    sessions.update(session, now);
    plans.updateFocusProgress(
        session.userId(), session.planItemId(), session.actualSeconds(), completed);
  }

  private FocusSession requireOwned(String userId, String sessionId) {
    return sessions
        .findOwned(userId, sessionId)
        .orElseThrow(
            () ->
                new BusinessException(HttpStatus.NOT_FOUND, "FOCUS_SESSION_NOT_FOUND", "专注会话不存在"));
  }

  private FocusView view(FocusSession session, Instant now) {
    return new FocusView(
        session.id(),
        session.planItemId(),
        session.mediaItemId(),
        session.focusType(),
        session.status(),
        session.plannedSeconds(),
        session.actualSecondsAt(now),
        session.startedAt(),
        session.runningSince(),
        session.pausedAt(),
        session.endedAt(),
        now);
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  public record StartCommand(
      String planItemId, String mediaItemId, String focusType, long plannedSeconds) {}

  public record FocusView(
      String id,
      String planItemId,
      String mediaItemId,
      String focusType,
      String status,
      long plannedSeconds,
      long actualSeconds,
      Instant startedAt,
      Instant runningSince,
      Instant pausedAt,
      Instant endedAt,
      Instant serverNow) {}
}
