package com.shangan.nag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.infrastructure.NagPolicyRepository;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 全局默认与用户覆盖的合并规则：只有阈值与渠道字段可被覆盖。 */
@ExtendWith(MockitoExtension.class)
class NagPolicyResolverTest {

  private static final String USER_ID = "user-1";

  @Mock private NagPolicyRepository policies;

  private NagPolicyResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new NagPolicyResolver(policies);
  }

  @Test
  @DisplayName("数据库没有全局策略时使用内置默认值")
  void 缺失全局策略时用内置默认() {
    when(policies.findGlobal()).thenReturn(Optional.empty());

    EffectiveNagPolicy policy = resolver.global();

    assertThat(policy.scanIntervalMinutes()).isEqualTo(5);
    assertThat(policy.presenceGraceSeconds()).isEqualTo(150);
    assertThat(policy.heartbeatIntervalSeconds()).isEqualTo(60);
    assertThat(policy.firstThresholdMinutes()).isEqualTo(90);
    assertThat(policy.repeatIntervalMinutes()).isEqualTo(60);
    assertThat(policy.dailyMax()).isEqualTo(3);
    assertThat(policy.fullscreenTimeoutMinutes()).isEqualTo(10);
    assertThat(policy.quietStart()).isEqualTo(LocalTime.of(23, 30));
    assertThat(policy.quietEnd()).isEqualTo(LocalTime.of(7, 0));
    assertThat(policy.minPending()).isEqualTo(1);
    assertThat(policy.minReasonLength()).isEqualTo(5);
    assertThat(policy.channelFullscreenEnabled()).isTrue();
    assertThat(policy.channelServerchanEnabled()).isTrue();
  }

  @Test
  @DisplayName("没有用户覆盖时直接返回全局策略")
  void 无覆盖时返回全局() {
    EffectiveNagPolicy global = policy(30, 20, 6, 4, false, true, "全局文案");
    when(policies.findGlobal()).thenReturn(Optional.of(stored("GLOBAL", null, global)));
    when(policies.findByUser(USER_ID)).thenReturn(Optional.empty());

    assertThat(resolver.resolve(USER_ID)).isEqualTo(global);
  }

  @Test
  @DisplayName("用户覆盖只接管阈值与渠道字段")
  void 覆盖接管阈值与渠道() {
    EffectiveNagPolicy global = policy(90, 60, 3, 5, true, true, "全局文案");
    EffectiveNagPolicy override = policy(30, 15, 8, 12, false, false, "个人文案");
    when(policies.findGlobal()).thenReturn(Optional.of(stored("GLOBAL", null, global)));
    when(policies.findByUser(USER_ID)).thenReturn(Optional.of(stored("USER", USER_ID, override)));

    EffectiveNagPolicy resolved = resolver.resolve(USER_ID);

    assertThat(resolved.firstThresholdMinutes()).isEqualTo(30);
    assertThat(resolved.repeatIntervalMinutes()).isEqualTo(15);
    assertThat(resolved.dailyMax()).isEqualTo(8);
    assertThat(resolved.minReasonLength()).isEqualTo(12);
    assertThat(resolved.channelFullscreenEnabled()).isFalse();
    assertThat(resolved.channelServerchanEnabled()).isFalse();
    assertThat(resolved.messageTemplate()).isEqualTo("个人文案");
  }

  @Test
  @DisplayName("调度类字段不可被用户覆盖：扫描周期、在线宽限与心跳间隔仍取全局值")
  void 调度字段不被覆盖() {
    EffectiveNagPolicy global = policy(90, 60, 3, 5, true, true, "全局文案");
    EffectiveNagPolicy override =
        new EffectiveNagPolicy(
            99,
            999,
            999,
            30,
            15,
            8,
            12,
            LocalTime.of(1, 0),
            LocalTime.of(2, 0),
            2,
            12,
            false,
            false,
            "个人文案",
            false,
            false,
            false);
    when(policies.findGlobal()).thenReturn(Optional.of(stored("GLOBAL", null, global)));
    when(policies.findByUser(USER_ID)).thenReturn(Optional.of(stored("USER", USER_ID, override)));

    EffectiveNagPolicy resolved = resolver.resolve(USER_ID);

    assertThat(resolved.scanIntervalMinutes()).isEqualTo(global.scanIntervalMinutes());
    assertThat(resolved.presenceGraceSeconds()).isEqualTo(global.presenceGraceSeconds());
    assertThat(resolved.heartbeatIntervalSeconds()).isEqualTo(global.heartbeatIntervalSeconds());
    // 免打扰与最少未完成属于阈值类字段，允许被覆盖。
    assertThat(resolved.quietStart()).isEqualTo(LocalTime.of(1, 0));
    assertThat(resolved.minPending()).isEqualTo(2);
  }

  @Test
  @DisplayName("覆盖行文案为空时回落到全局文案")
  void 覆盖文案为空回落全局() {
    EffectiveNagPolicy global = policy(90, 60, 3, 5, true, true, "全局文案");
    EffectiveNagPolicy override = policy(30, 15, 8, 12, true, true, "   ");
    when(policies.findGlobal()).thenReturn(Optional.of(stored("GLOBAL", null, global)));
    when(policies.findByUser(USER_ID)).thenReturn(Optional.of(stored("USER", USER_ID, override)));

    assertThat(resolver.resolve(USER_ID).messageTemplate()).isEqualTo("全局文案");
  }

  private static NagPolicyRepository.StoredPolicy stored(
      String scope, String userId, EffectiveNagPolicy policy) {
    return new NagPolicyRepository.StoredPolicy("policy-1", scope, userId, policy);
  }

  private static EffectiveNagPolicy policy(
      int firstThreshold,
      int repeatInterval,
      int dailyMax,
      int minReasonLength,
      boolean fullscreen,
      boolean serverchan,
      String template) {
    return new EffectiveNagPolicy(
        5,
        150,
        60,
        firstThreshold,
        repeatInterval,
        dailyMax,
        10,
        LocalTime.of(23, 30),
        LocalTime.of(7, 0),
        1,
        minReasonLength,
        fullscreen,
        serverchan,
        template,
        true,
        true,
        true);
  }
}
