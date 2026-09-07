package com.shangan.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shangan.common.api.BusinessException;
import com.shangan.debt.application.DebtService;
import com.shangan.identity.infrastructure.UserRepository;
import com.shangan.learning.api.WatchHeartbeatRequest;
import com.shangan.learning.application.AliveCheckScheduler;
import com.shangan.learning.application.WatchSessionService;
import com.shangan.learning.infrastructure.*;
import com.shangan.planning.application.PlanProgressPort;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 不连接数据库，验证固定步长授权、幂等、验活和进度对账边界。 */
class WatchFastForwardServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-07T00:00:10Z");
  private final WatchSessionRepository sessions = mock(WatchSessionRepository.class);
  private final VideoProgressRepository progress = mock(VideoProgressRepository.class);
  private final PlanProgressPort plans = mock(PlanProgressPort.class);
  private final DebtService debts = mock(DebtService.class);
  private final WatchSessionService service =
      new WatchSessionService(
          sessions,
          progress,
          mock(ReviewEventRepository.class),
          plans,
          debts,
          mock(UserRepository.class),
          new AliveCheckScheduler(),
          () -> "generated",
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void prepare() {
    when(sessions.findOwned("user", "session"))
        .thenReturn(Optional.of(session("ACTIVE", 0, 0, null, false)));
    when(sessions.updateHeartbeat(eq("session"), any(), anyBoolean(), anyString(), eq(NOW)))
        .thenReturn(true);
    when(progress.synchronize(
            anyString(), eq("user"), eq("lesson"), anyLong(), anyLong(), anyBoolean(), eq(NOW)))
        .thenAnswer(
            call ->
                new VideoProgressRepository.Progress(
                    "progress", "user", "lesson", call.getArgument(3), call.getArgument(4), null));
  }

  @Test
  void authorizesTenSecondsAndSynchronizesOnlyActualWatchTime() {
    var result = service.fastForward("user", "session", request(1, 10_000));
    assertThat(result.positionMs()).isEqualTo(20_000);
    assertThat(result.progress().trustedPositionMs()).isEqualTo(20_000);
    assertThat(result.progress().verifiedWatchMs()).isEqualTo(10_000);
    verify(plans).updateVideoWatchProgress("user", "item", 20, false);
    verify(debts).reconcileOpenVideoDebt("user", "lesson", 20, false, NOW);
  }

  @Test
  void repeatedCompletedRequestDoesNotAdvanceOrSynchronizeAgain() {
    when(sessions.findOwned("user", "session"))
        .thenReturn(Optional.of(session("COMPLETED", 100_000, 5, null, false)));
    var result = service.fastForward("user", "session", request(5, 90_000));
    assertThat(result.positionMs()).isEqualTo(100_000);
    assertThat(result.progress().completed()).isTrue();
    verifyNoInteractions(progress, plans, debts);
    verify(sessions, never()).updateHeartbeat(anyString(), any(), anyBoolean(), anyString(), any());
  }

  @Test
  void crossingCheckpointCreatesPendingAliveCheck() {
    when(sessions.findOwned("user", "session"))
        .thenReturn(Optional.of(session("ACTIVE", 0, 0, 15_000L, false)));
    var result = service.fastForward("user", "session", request(1, 10_000));
    assertThat(result.progress().aliveCheckRequired()).isTrue();
    assertThat(result.progress().status()).isEqualTo("PAUSED");
    verify(sessions).insertAliveCheck("generated", "session", NOW);
  }

  @Test
  void reachingThresholdCompletesAndReconcilesDebtWithoutAddingSkippedTime() {
    when(sessions.findOwned("user", "session"))
        .thenReturn(Optional.of(session("ACTIVE", 90_000, 1, null, false)));
    var result =
        service.fastForward(
            "user", "session", new WatchHeartbeatRequest(2, 90_000, false, true, 1));
    assertThat(result.positionMs()).isEqualTo(100_000);
    assertThat(result.progress().completed()).isTrue();
    assertThat(result.progress().verifiedWatchMs()).isZero();
    verify(plans).updateVideoWatchProgress("user", "item", 100, true);
    verify(debts).reconcileOpenVideoDebt("user", "lesson", 100, true, NOW);
  }

  @Test
  void rejectsForeignClosedBackgroundAndPendingSessions() {
    assertThatThrownBy(() -> service.fastForward("other", "session", request(1, 0)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("观看会话不存在");
    when(sessions.findOwned("user", "session"))
        .thenReturn(Optional.of(session("STOPPED", 0, 0, null, false)));
    assertThatThrownBy(() -> service.fastForward("user", "session", request(1, 0)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("观看会话已经结束");
    when(sessions.findOwned("user", "session"))
        .thenReturn(Optional.of(session("PAUSED", 0, 0, null, true)));
    assertThatThrownBy(() -> service.fastForward("user", "session", request(1, 0)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("请先完成验活");
    when(sessions.findOwned("user", "session"))
        .thenReturn(Optional.of(session("ACTIVE", 0, 0, null, false)));
    assertThatThrownBy(
            () ->
                service.fastForward(
                    "user", "session", new WatchHeartbeatRequest(1, 0, true, false, 1)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("后台不能快进");
    verifyNoInteractions(progress, plans, debts);
  }

  private WatchHeartbeatRequest request(long sequence, long position) {
    return new WatchHeartbeatRequest(sequence, position, true, true, 1);
  }

  private WatchSessionRepository.Session session(
      String status, long position, long sequence, Long due, boolean pending) {
    return new WatchSessionRepository.Session(
        "session",
        "user",
        "lesson",
        "item",
        status,
        100_000,
        position,
        position,
        0,
        0,
        sequence,
        NOW.minusSeconds(10),
        due,
        pending);
  }
}
