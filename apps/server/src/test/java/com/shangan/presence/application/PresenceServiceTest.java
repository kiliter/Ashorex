package com.shangan.presence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.identity.application.UserTimeService;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.nag.application.NagResponseService;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import com.shangan.presence.domain.PresenceSnapshot;
import com.shangan.presence.domain.PresenceState;
import com.shangan.presence.infrastructure.UserPresenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 心跳服务：只更新心跳、下发间隔与待回应催办，不刷新有效操作。 */
@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T04:00:00Z");
  private static final String USER_ID = "user-1";
  private static final com.shangan.identity.domain.User USER =
      new com.shangan.identity.domain.User(
          USER_ID,
          "demo",
          "hash",
          "小明",
          "Asia/Shanghai",
          com.shangan.identity.domain.UserStatus.ACTIVE,
          null,
          Set.of(com.shangan.identity.domain.UserRole.LEARNER));

  @Mock private UserPresenceRepository presence;
  @Mock private NagPolicyResolver policies;
  @Mock private NagResponseService nagResponses;
  @Mock private UserTimeService userTime;

  private PresenceService service;

  @BeforeEach
  void setUp() {
    service =
        new PresenceService(
            presence,
            policies,
            nagResponses,
            userTime,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new com.shangan.nag.application.NagTransportService(
                org.mockito.Mockito.mock(com.shangan.nag.infrastructure.NagPolicyRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC)));
  }

  @Test
  @DisplayName("心跳只写心跳时间与前后台状态，绝不写有效操作时间")
  void 心跳不写有效操作() {
    when(policies.resolve(USER_ID)).thenReturn(policy(60, 5));
    when(nagResponses.pending(USER_ID)).thenReturn(Optional.empty());

    PresenceService.HeartbeatResponse response = service.heartbeat(USER_ID, "FOREGROUND", "2.0.0");

    verify(presence).recordHeartbeat(USER_ID, NOW, "FOREGROUND", "2.0.0");
    verify(presence, never()).recordEffectiveAction(anyString(), any());
    assertThat(response.serverTime()).isEqualTo(NOW);
    assertThat(response.heartbeatIntervalSeconds()).isEqualTo(60);
    assertThat(response.pendingNagId()).isNull();
    assertThat(response.pendingNagMessage()).isNull();
    assertThat(response.requireReason()).isFalse();
    assertThat(response.minReasonLength()).isEqualTo(5);
  }

  @Test
  @DisplayName("未知或缺失的前后台状态统一归一为 BACKGROUND")
  void 前后台状态归一() {
    when(policies.resolve(USER_ID)).thenReturn(policy(60, 5));
    when(nagResponses.pending(USER_ID)).thenReturn(Optional.empty());

    service.heartbeat(USER_ID, null, "2.0.0");

    verify(presence).recordHeartbeat(USER_ID, NOW, "BACKGROUND", "2.0.0");
  }

  @Test
  @DisplayName("有待回应催办时心跳响应下发催办 ID、文案与原因要求")
  void 心跳下发待回应催办() {
    when(policies.resolve(USER_ID)).thenReturn(policy(45, 8));
    when(nagResponses.pending(USER_ID)).thenReturn(Optional.of(nag()));

    PresenceService.HeartbeatResponse response = service.heartbeat(USER_ID, "foreground", "2.0.0");

    assertThat(response.pendingNagId()).isEqualTo("nag-1");
    assertThat(response.pendingNagMessage()).isEqualTo("还有 3 项没做");
    assertThat(response.requireReason()).isTrue();
    assertThat(response.minReasonLength()).isEqualTo(8);
    assertThat(response.heartbeatIntervalSeconds()).isEqualTo(45);
    verify(presence).recordHeartbeat(USER_ID, NOW, "FOREGROUND", "2.0.0");
  }

  @Test
  @DisplayName("在线视图：心跳在宽限期内且刚有操作时为 ONLINE，并带上用户本地日期")
  void 在线视图为在线() {
    when(policies.resolve(USER_ID)).thenReturn(policy(60, 5));
    when(presence.find(USER_ID))
        .thenReturn(
            Optional.of(
                new PresenceSnapshot(
                    USER_ID, NOW.minusSeconds(20), NOW.minusSeconds(600), "FOREGROUND", "2.0.0")));
    when(userTime.today(USER)).thenReturn(LocalDate.of(2026, 9, 7));

    PresenceService.PresenceView view = service.view(USER);

    assertThat(view.state()).isEqualTo(PresenceState.ONLINE);
    assertThat(view.idleMinutes()).isEqualTo(10);
    assertThat(view.appState()).isEqualTo("FOREGROUND");
    assertThat(view.clientVersion()).isEqualTo("2.0.0");
    assertThat(view.localDate()).isEqualTo("2026-09-07");
  }

  @Test
  @DisplayName("从未有过有效操作时视图把空闲分钟数折叠为 -1")
  void 从未操作时空闲为负一() {
    when(policies.resolve(USER_ID)).thenReturn(policy(60, 5));
    when(presence.find(USER_ID)).thenReturn(Optional.empty());
    when(userTime.today(USER)).thenReturn(LocalDate.of(2026, 9, 7));

    PresenceService.PresenceView view = service.view(USER);

    assertThat(view.state()).isEqualTo(PresenceState.OFFLINE);
    assertThat(view.idleMinutes()).isEqualTo(-1);
    assertThat(view.lastHeartbeatAt()).isNull();
    assertThat(view.lastEffectiveActionAt()).isNull();
  }

  private static Nag nag() {
    return new Nag(
        "nag-1",
        USER_ID,
        LocalDate.of(2026, 9, 7),
        1,
        NagTrigger.AUTO,
        null,
        95,
        3,
        "还有 3 项没做",
        true,
        NagStatus.DELIVERED,
        NOW.minusSeconds(60),
        null,
        null,
        null,
        null,
        NOW.minusSeconds(60));
  }

  private static EffectiveNagPolicy policy(int heartbeatIntervalSeconds, int minReasonLength) {
    return new EffectiveNagPolicy(
        5,
        150,
        heartbeatIntervalSeconds,
        90,
        60,
        3,
        10,
        LocalTime.of(23, 30),
        LocalTime.of(7, 0),
        1,
        minReasonLength,
        true,
        true,
        "{{user}}",
        true,
        true,
        true);
  }
}
