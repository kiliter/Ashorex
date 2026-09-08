package com.shangan.nag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 催办扫描判定：未完成数、免打扰、每日上限、幂等键与档位。 */
@ExtendWith(MockitoExtension.class)
class NagScannerTest {

  private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
  private static final User USER =
      new User(
          "user-1",
          "demo",
          "hash",
          "小明",
          "Asia/Shanghai",
          UserStatus.ACTIVE,
          null,
          Set.of(UserRole.LEARNER));

  @Mock private AuthService users;
  @Mock private UserTimeService userTime;
  @Mock private TodoRepository todos;
  @Mock private UserPresenceRepository presence;
  @Mock private NagRepository nags;
  @Mock private NagPolicyResolver policies;
  @Mock private NagDeliveryService delivery;
  @Mock private SupervisionService supervisions;

  private NagScanner scanner;

  @BeforeEach
  void setUp() {
    scanner =
        new NagScanner(
            users,
            userTime,
            todos,
            presence,
            nags,
            policies,
            delivery,
            supervisions,
            () -> "nag-1",
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("空闲达到阈值且有未完成时创建催办，并按档位与文案投递")
  void 达到阈值创建催办() {
    stubUser(3, LocalTime.of(10, 0), 0, idleMinutes(95));
    when(supervisions.primarySupervisorOf("user-1")).thenReturn(Optional.of("supervisor-1"));
    when(nags.autoNagExists("user-1", TODAY, 1)).thenReturn(false);

    Optional<Nag> created = scanner.createAutoNag(USER);

    assertThat(created).isPresent();
    ArgumentCaptor<Nag> nag = ArgumentCaptor.forClass(Nag.class);
    verify(nags).insert(nag.capture());
    assertThat(nag.getValue().id()).isEqualTo("nag-1");
    assertThat(nag.getValue().userId()).isEqualTo("user-1");
    assertThat(nag.getValue().localDate()).isEqualTo(TODAY);
    assertThat(nag.getValue().thresholdLevel()).isEqualTo(1);
    assertThat(nag.getValue().trigger()).isEqualTo(NagTrigger.AUTO);
    assertThat(nag.getValue().status()).isEqualTo(NagStatus.PENDING);
    assertThat(nag.getValue().pendingCount()).isEqualTo(3);
    assertThat(nag.getValue().idleMinutes()).isEqualTo(95);
    assertThat(nag.getValue().requireReason()).isTrue();
    assertThat(nag.getValue().supervisorUserIdSnapshot()).isEqualTo("supervisor-1");
    assertThat(nag.getValue().message()).isEqualTo("小明 今天还有 3 项未完成，已经 95 分钟没有任何操作。");
    assertThat(nag.getValue().createdAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("同一天同一档位已存在自动催办时幂等跳过")
  void 同档位幂等跳过() {
    stubUser(3, LocalTime.of(10, 0), 0, idleMinutes(95));
    when(nags.autoNagExists("user-1", TODAY, 1)).thenReturn(true);

    assertThat(scanner.createAutoNag(USER)).isEmpty();
    verify(nags, never()).insert(any());
    verify(delivery, never()).deliver(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("空闲跨过重复间隔后档位提升为 2，幂等键随之变化")
  void 档位提升后重新催办() {
    stubUser(3, LocalTime.of(10, 0), 0, idleMinutes(150));
    when(supervisions.primarySupervisorOf("user-1")).thenReturn(Optional.empty());
    when(nags.autoNagExists("user-1", TODAY, 2)).thenReturn(false);

    assertThat(scanner.createAutoNag(USER)).isPresent();
    ArgumentCaptor<Nag> nag = ArgumentCaptor.forClass(Nag.class);
    verify(nags).insert(nag.capture());
    assertThat(nag.getValue().thresholdLevel()).isEqualTo(2);
  }

  @Test
  @DisplayName("未完成数低于策略下限时不催办")
  void 未完成不足不催办() {
    when(policies.resolve("user-1"))
        .thenReturn(policy(2, 3, LocalTime.of(23, 30), LocalTime.of(7, 0)));
    when(userTime.today(USER)).thenReturn(TODAY);
    when(todos.countPendingOn("user-1", TODAY)).thenReturn(1);

    assertThat(scanner.createAutoNag(USER)).isEmpty();
    verify(nags, never()).insert(any());
  }

  @Test
  @DisplayName("处于免打扰时段时不投递")
  void 免打扰时段不催办() {
    when(policies.resolve("user-1"))
        .thenReturn(policy(1, 3, LocalTime.of(23, 30), LocalTime.of(7, 0)));
    when(userTime.today(USER)).thenReturn(TODAY);
    when(todos.countPendingOn("user-1", TODAY)).thenReturn(3);
    when(userTime.localTimeNow(USER)).thenReturn(LocalTime.of(1, 30));

    assertThat(scanner.createAutoNag(USER)).isEmpty();
    verify(nags, never()).insert(any());
  }

  @Test
  @DisplayName("当日催办次数达到上限后不再新建")
  void 每日上限用尽不催办() {
    when(policies.resolve("user-1"))
        .thenReturn(policy(1, 3, LocalTime.of(23, 30), LocalTime.of(7, 0)));
    when(userTime.today(USER)).thenReturn(TODAY);
    when(todos.countPendingOn("user-1", TODAY)).thenReturn(3);
    when(userTime.localTimeNow(USER)).thenReturn(LocalTime.of(10, 0));
    when(nags.countOn("user-1", TODAY)).thenReturn(3);

    assertThat(scanner.createAutoNag(USER)).isEmpty();
    verify(nags, never()).insert(any());
  }

  @Test
  @DisplayName("空闲时长未达首次阈值时不催办")
  void 空闲不足不催办() {
    stubUser(3, LocalTime.of(10, 0), 0, idleMinutes(89));

    assertThat(scanner.createAutoNag(USER)).isEmpty();
    verify(nags, never()).insert(any());
  }

  @Test
  @DisplayName("手动催办不参与自动幂等键，档位归一为 1 并记录发起人")
  void 手动催办记录发起人() {
    when(userTime.requireUser("user-1")).thenReturn(USER);
    when(policies.resolve("user-1"))
        .thenReturn(policy(1, 3, LocalTime.of(23, 30), LocalTime.of(7, 0)));
    when(userTime.today(USER)).thenReturn(TODAY);
    when(nags.countOn("user-1", TODAY)).thenReturn(1);
    when(presence.find("user-1")).thenReturn(Optional.of(idleMinutes(30)));
    when(todos.countPendingOn("user-1", TODAY)).thenReturn(2);
    when(supervisions.primarySupervisorOf("user-1")).thenReturn(Optional.of("supervisor-1"));

    Nag nag =
        scanner.createManualNag(
            "user-1",
            "supervisor-1",
            NagTrigger.SUPERVISOR,
            "  别摸鱼了  ",
            NagChannelType.FULLSCREEN,
            false);

    assertThat(nag.trigger()).isEqualTo(NagTrigger.SUPERVISOR);
    assertThat(nag.triggeredByUserId()).isEqualTo("supervisor-1");
    assertThat(nag.thresholdLevel()).isEqualTo(1);
    assertThat(nag.message()).isEqualTo("别摸鱼了");
    assertThat(nag.requireReason()).isFalse();
    verify(nags).insert(nag);
    verify(nags, never()).autoNagExists(any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  @DisplayName("手动催办同样受每日上限约束，超限返回 NAG_DAILY_LIMIT_REACHED")
  void 手动催办超限被拒() {
    when(userTime.requireUser("user-1")).thenReturn(USER);
    when(policies.resolve("user-1"))
        .thenReturn(policy(1, 3, LocalTime.of(23, 30), LocalTime.of(7, 0)));
    when(userTime.today(USER)).thenReturn(TODAY);
    when(nags.countOn("user-1", TODAY)).thenReturn(3);

    assertThatThrownBy(
            () ->
                scanner.createManualNag("user-1", "admin-1", NagTrigger.MANUAL, "起来学习", null, true))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("NAG_DAILY_LIMIT_REACHED");
    verify(nags, never()).insert(any());
  }

  @Test
  @DisplayName("手动催办未填文案时回落到策略模板")
  void 手动催办空文案回落模板() {
    when(userTime.requireUser("user-1")).thenReturn(USER);
    when(policies.resolve("user-1"))
        .thenReturn(policy(1, 3, LocalTime.of(23, 30), LocalTime.of(7, 0)));
    when(userTime.today(USER)).thenReturn(TODAY);
    when(nags.countOn("user-1", TODAY)).thenReturn(0);
    when(presence.find("user-1")).thenReturn(Optional.of(idleMinutes(42)));
    when(todos.countPendingOn("user-1", TODAY)).thenReturn(2);
    when(supervisions.primarySupervisorOf("user-1")).thenReturn(Optional.empty());

    Nag nag = scanner.createManualNag("user-1", "admin-1", NagTrigger.MANUAL, "   ", null, true);

    assertThat(nag.message()).isEqualTo("小明 今天还有 2 项未完成，已经 42 分钟没有任何操作。");
  }

  private void stubUser(
      int pendingCount, LocalTime localTime, int nagsToday, PresenceSnapshot snapshot) {
    when(policies.resolve("user-1"))
        .thenReturn(policy(1, 3, LocalTime.of(23, 30), LocalTime.of(7, 0)));
    when(userTime.today(USER)).thenReturn(TODAY);
    when(todos.countPendingOn("user-1", TODAY)).thenReturn(pendingCount);
    when(userTime.localTimeNow(USER)).thenReturn(localTime);
    when(nags.countOn("user-1", TODAY)).thenReturn(nagsToday);
    when(presence.find("user-1")).thenReturn(Optional.of(snapshot));
  }

  private static PresenceSnapshot idleMinutes(long minutes) {
    return new PresenceSnapshot(
        "user-1",
        NOW.minusSeconds(30),
        NOW.minus(java.time.Duration.ofMinutes(minutes)),
        "FOREGROUND",
        "2.0.0");
  }

  private static EffectiveNagPolicy policy(
      int minPending, int dailyMax, LocalTime quietStart, LocalTime quietEnd) {
    return new EffectiveNagPolicy(
        5,
        150,
        60,
        90,
        60,
        dailyMax,
        10,
        quietStart,
        quietEnd,
        minPending,
        5,
        true,
        true,
        "{{user}} 今天还有 {{pending}} 项未完成，已经 {{idleMinutes}} 分钟没有任何操作。",
        true,
        true,
        true);
  }
}
