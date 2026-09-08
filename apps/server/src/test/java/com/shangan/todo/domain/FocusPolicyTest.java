package com.shangan.todo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 专注状态机的合法与非法转移矩阵。 */
class FocusPolicyTest {

  @Test
  @DisplayName("IDLE 只能开始，不能直接暂停、完成或放弃")
  void 空闲态只能开始() {
    assertThat(allowedFrom(FocusState.IDLE))
        .containsExactlyInAnyOrder(FocusState.RUNNING, FocusState.ABANDONED);
  }

  @Test
  @DisplayName("RUNNING 可以暂停、完成或放弃，但不能再次开始")
  void 运行态三条出边() {
    assertThat(allowedFrom(FocusState.RUNNING))
        .containsExactlyInAnyOrder(
            FocusState.PAUSED, FocusState.STOPPED, FocusState.FINISHED, FocusState.ABANDONED);
  }

  @Test
  @DisplayName("PAUSED 可以恢复、完成或放弃")
  void 暂停态三条出边() {
    assertThat(allowedFrom(FocusState.PAUSED))
        .containsExactlyInAnyOrder(
            FocusState.RUNNING, FocusState.STOPPED, FocusState.FINISHED, FocusState.ABANDONED);
  }

  @Test
  @DisplayName("FINISHED 与 ABANDONED 都是终态，不允许任何再转移")
  void 两种终态不可再转移() {
    assertThat(allowedFrom(FocusState.FINISHED)).isEmpty();
    assertThat(allowedFrom(FocusState.ABANDONED)).isEmpty();
    assertThat(FocusState.FINISHED.terminal()).isTrue();
    assertThat(FocusState.ABANDONED.terminal()).isTrue();
  }

  @Test
  @DisplayName("只有 RUNNING 与 PAUSED 属于进行中状态")
  void 进行中状态判定() {
    assertThat(FocusState.RUNNING.active()).isTrue();
    assertThat(FocusState.PAUSED.active()).isTrue();
    assertThat(FocusState.IDLE.active()).isFalse();
    assertThat(FocusState.FINISHED.active()).isFalse();
    assertThat(FocusState.ABANDONED.active()).isFalse();
  }

  @Test
  @DisplayName("停止后允许再次开始或跳过")
  void 停止后允许再次开始或跳过() {
    assertThat(allowedFrom(FocusState.STOPPED))
        .containsExactlyInAnyOrder(FocusState.RUNNING, FocusState.ABANDONED);
  }

  private Set<FocusState> allowedFrom(FocusState from) {
    Set<FocusState> allowed = EnumSet.noneOf(FocusState.class);
    for (FocusState target : FocusState.values()) {
      if (from.canTransitionTo(target)) {
        allowed.add(target);
      }
    }
    return allowed;
  }
}
