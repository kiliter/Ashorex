package com.shangan.presence.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 在线与空闲判定的纯规则测试。
 *
 * <p>核心约束：在线判定看最近心跳，空闲判定看最近一次有效操作，心跳不参与空闲计算。
 */
class PresencePolicyTest {

  private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
  private static final Duration GRACE = Duration.ofSeconds(150);
  private static final Duration IDLE_THRESHOLD = Duration.ofMinutes(90);

  @Test
  @DisplayName("心跳在宽限期内且有效操作很近时判定为 ONLINE")
  void 宽限期内且刚有操作为在线() {
    PresenceSnapshot snapshot = snapshot(NOW.minusSeconds(30), NOW.minus(Duration.ofMinutes(10)));

    assertThat(snapshot.state(NOW, GRACE, IDLE_THRESHOLD)).isEqualTo(PresenceState.ONLINE);
    assertThat(snapshot.online(NOW, GRACE)).isTrue();
    assertThat(snapshot.idleMinutes(NOW)).isEqualTo(10);
  }

  @Test
  @DisplayName("心跳正好落在宽限期边界上仍算在线，超出一秒即离线")
  void 宽限期边界判定() {
    assertThat(snapshot(NOW.minusSeconds(150), NOW).online(NOW, GRACE)).isTrue();
    assertThat(snapshot(NOW.minusSeconds(151), NOW).online(NOW, GRACE)).isFalse();
    assertThat(snapshot(NOW.minusSeconds(151), NOW).state(NOW, GRACE, IDLE_THRESHOLD))
        .isEqualTo(PresenceState.OFFLINE);
  }

  @Test
  @DisplayName("心跳在线但空闲达到阈值时判定为 IDLE")
  void 在线但空闲达到阈值为空闲() {
    PresenceSnapshot exactly = snapshot(NOW.minusSeconds(10), NOW.minus(Duration.ofMinutes(90)));
    PresenceSnapshot notYet = snapshot(NOW.minusSeconds(10), NOW.minus(Duration.ofMinutes(89)));

    assertThat(exactly.state(NOW, GRACE, IDLE_THRESHOLD)).isEqualTo(PresenceState.IDLE);
    assertThat(notYet.state(NOW, GRACE, IDLE_THRESHOLD)).isEqualTo(PresenceState.ONLINE);
  }

  @Test
  @DisplayName("从未心跳过一律离线")
  void 从未心跳为离线() {
    PresenceSnapshot snapshot = PresenceSnapshot.empty("user-1");

    assertThat(snapshot.state(NOW, GRACE, IDLE_THRESHOLD)).isEqualTo(PresenceState.OFFLINE);
    assertThat(snapshot.online(NOW, GRACE)).isFalse();
    assertThat(snapshot.appState()).isEqualTo("BACKGROUND");
  }

  @Test
  @DisplayName("心跳不刷新有效操作：只更新心跳时间时空闲时长继续增长")
  void 心跳不刷新空闲时长() {
    Instant lastAction = NOW.minus(Duration.ofMinutes(120));
    PresenceSnapshot beforeHeartbeat = snapshot(NOW.minus(Duration.ofMinutes(3)), lastAction);
    // 模拟一次心跳：只替换 lastHeartbeatAt，lastEffectiveActionAt 保持不变。
    PresenceSnapshot afterHeartbeat = snapshot(NOW, lastAction);

    assertThat(afterHeartbeat.idleMinutes(NOW)).isEqualTo(beforeHeartbeat.idleMinutes(NOW));
    assertThat(afterHeartbeat.idleMinutes(NOW)).isEqualTo(120);
    assertThat(afterHeartbeat.state(NOW, GRACE, IDLE_THRESHOLD)).isEqualTo(PresenceState.IDLE);
  }

  @Test
  @DisplayName("从未有过有效操作时空闲时长按极大值处理，保证首次催办能生效")
  void 从未有效操作时空闲为极大值() {
    PresenceSnapshot snapshot = snapshot(NOW, null);

    assertThat(snapshot.idleMinutes(NOW)).isEqualTo(Long.MAX_VALUE / 2);
  }

  private PresenceSnapshot snapshot(Instant lastHeartbeatAt, Instant lastEffectiveActionAt) {
    return new PresenceSnapshot(
        "user-1", lastHeartbeatAt, lastEffectiveActionAt, "FOREGROUND", "2.0.0");
  }
}
