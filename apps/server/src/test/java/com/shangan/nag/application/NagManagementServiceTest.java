package com.shangan.nag.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.*;
import com.shangan.nag.domain.*;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.infrastructure.UserPresenceRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;

/** 管理操作只允许处理全失败记录，测试不连接数据库。 */
class NagManagementServiceTest {
  final NagRepository nags = mock(NagRepository.class);
  final NagDeliveryService delivery = mock(NagDeliveryService.class);
  final NagPolicyResolver policies = mock(NagPolicyResolver.class);
  final UserTimeService time = mock(UserTimeService.class);
  final UserPresenceRepository presence = mock(UserPresenceRepository.class);
  final EffectiveNagPolicy policy = mock(EffectiveNagPolicy.class);
  final Instant now = Instant.parse("2026-09-09T04:00:00Z");
  final NagManagementService service =
      new NagManagementService(
          nags,
          delivery,
          policies,
          time,
          presence,
          Clock.fixed(now, ZoneOffset.UTC),
          mock(PlatformTransactionManager.class));

  @BeforeEach
  void prepare() {
    when(nags.findById("n")).thenReturn(Optional.of(nag(NagStatus.PENDING)));
    when(nags.deliveriesOf("n"))
        .thenReturn(
            List.of(
                new NagRepository.Delivery(
                    "d", "n", NagChannelType.SERVERCHAN, "FAILED", "失败", now)));
    when(time.today("u")).thenReturn(LocalDate.of(2026, 9, 9));
    when(time.requireUser("u"))
        .thenReturn(new User("u", "user", "", "学员", "UTC", UserStatus.ACTIVE, null, Set.of()));
    when(policies.resolve("u")).thenReturn(policy);
    when(policy.channelFullscreenEnabled()).thenReturn(true);
    when(nags.cancelFailed("n")).thenReturn(true);
  }

  @Test
  void 取消保留历史并记录管理员() {
    service.cancel("n", "d", "admin");
    verify(nags).cancelFailed("n");
    verify(nags).recordAdminAction(eq("n"), eq("CANCEL"), eq("admin"), eq(now));
    verifyNoInteractions(delivery);
  }

  @Test
  void 重投复用原记录不新建催办() {
    service.retry("n", "d", "admin");
    verify(delivery).deliver(eq(nag(NagStatus.PENDING)), eq("学员"), isNull(), eq(policy), isNull());
    verify(nags, never()).insert(any());
    verify(nags).recordAdminAction("n", "RETRY", "admin", now);
  }

  @Test
  void 终态不能取消或重投() {
    for (NagStatus state :
        List.of(NagStatus.DELIVERED, NagStatus.RESPONDED, NagStatus.EXPIRED, NagStatus.CANCELLED)) {
      when(nags.findById("n")).thenReturn(Optional.of(nag(state)));
      assertThatThrownBy(() -> service.retry("n", "d", "admin")).hasMessageContaining("状态已变化");
      assertThatThrownBy(() -> service.cancel("n", "d", "admin")).hasMessageContaining("状态已变化");
    }
    verifyNoInteractions(delivery);
  }

  @Test
  void 无尝试或存在成功流水均拒绝() {
    when(nags.deliveriesOf("n")).thenReturn(List.of());
    assertThatThrownBy(() -> service.cancel("n", "d", "admin")).hasMessageContaining("状态已变化");
    when(nags.deliveriesOf("n"))
        .thenReturn(
            List.of(
                new NagRepository.Delivery(
                    "d", "n", NagChannelType.FULLSCREEN, "SENT", "成功", now)));
    assertThatThrownBy(() -> service.retry("n", "d", "admin")).hasMessageContaining("状态已变化");
  }

  @Test
  void 旧页面的重复请求不得再次发送() {
    assertThatThrownBy(() -> service.retry("n", "old", "admin")).hasMessageContaining("状态已变化");
    verifyNoInteractions(delivery);
  }

  @Test
  void 过期记录不能重新投递() {
    when(time.today("u")).thenReturn(LocalDate.of(2026, 9, 10));
    assertThatThrownBy(() -> service.retry("n", "d", "admin")).hasMessageContaining("过期");
    verifyNoInteractions(delivery);
  }

  @Test
  void 重投在途时取消和第二次重投均拒绝() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(delivery)
        .deliver(any(), anyString(), any(), any(), any());
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> service.retry("n", "d", "admin"));
      try {
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.retry("n", "d", "admin")).hasMessageContaining("正在处理");
        assertThatThrownBy(() -> service.cancel("n", "d", "admin")).hasMessageContaining("正在处理");
      } finally {
        release.countDown();
      }
      first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    verify(delivery, times(1)).deliver(any(), anyString(), any(), any(), any());
  }

  @Test
  void 重投失败可以在刷新后继续重试() {
    doAnswer(
            call -> {
              when(nags.deliveriesOf("n"))
                  .thenReturn(
                      List.of(
                          new NagRepository.Delivery(
                              "d2", "n", NagChannelType.SERVERCHAN, "FAILED", "仍失败", now)));
              return null;
            })
        .when(delivery)
        .deliver(any(), anyString(), any(), any(), any());
    service.retry("n", "d", "admin");
    assertThatThrownBy(() -> service.retry("n", "d", "admin")).hasMessageContaining("状态已变化");
    service.retry("n", "d2", "admin");
    verify(delivery, times(2)).deliver(any(), anyString(), any(), any(), any());
  }

  private Nag nag(NagStatus state) {
    return new Nag(
        "n",
        "u",
        LocalDate.of(2026, 9, 9),
        1,
        NagTrigger.MANUAL,
        null,
        10,
        1,
        "原正文",
        true,
        state,
        null,
        null,
        null,
        null,
        null,
        now,
        "原标题");
  }
}
