package com.shangan.focus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.focus.domain.FocusSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证专注计时状态机只根据服务端时间累计，并拒绝所有终态后续操作。 */
class FocusSessionStateMachineTest {
  private static final Instant START = Instant.parse("2026-08-30T00:00:00Z");

  @Test
  void runsPausesResumesAndFinishesWithExactServerElapsedSeconds() {
    FocusSession session =
        FocusSession.start("focus-1", "user-1", "item-1", null, "POMODORO", 1500, START);

    session.pause(START.plusSeconds(60));
    assertThat(session.status()).isEqualTo("PAUSED");
    assertThat(session.actualSeconds()).isEqualTo(60);

    session.resume(START.plusSeconds(120));
    session.finish(START.plusSeconds(150));

    assertThat(session.status()).isEqualTo("FINISHED");
    assertThat(session.actualSeconds()).isEqualTo(90);
    assertThat(session.endedAt()).isEqualTo(START.plusSeconds(150));
  }

  @Test
  void cancellationPreservesElapsedTimeAndTerminalStatesRejectTransitions() {
    FocusSession session =
        FocusSession.start("focus-2", "user-1", null, null, "MOCK_EXAM", 3600, START);

    session.cancel(START.plusSeconds(45));

    assertThat(session.status()).isEqualTo("CANCELLED");
    assertThat(session.actualSeconds()).isEqualTo(45);
    assertThatThrownBy(() -> session.resume(START.plusSeconds(46)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error ->
                assertThat(((BusinessException) error).errorCode())
                    .isEqualTo("FOCUS_ILLEGAL_TRANSITION"));
  }
}
