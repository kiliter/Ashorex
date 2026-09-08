package com.shangan.todo.domain;

/**
 * 专注 Todo 的内部状态机。
 *
 * <p>{@code FINISHED} 表示倒计时自然结束并判定完成；{@code ABANDONED} 表示跳过，Todo 保持未完成 但已累计的专注时长仍进入统计。
 */
public enum FocusState {
  IDLE,
  RUNNING,
  PAUSED,
  STOPPED,
  FINISHED,
  ABANDONED;

  /** 是否允许从当前状态转移到目标状态。 */
  public boolean canTransitionTo(FocusState target) {
    return switch (this) {
      case IDLE, STOPPED -> target == RUNNING || target == ABANDONED;
      case RUNNING ->
          target == PAUSED || target == STOPPED || target == FINISHED || target == ABANDONED;
      case PAUSED ->
          target == RUNNING || target == STOPPED || target == FINISHED || target == ABANDONED;
      case FINISHED, ABANDONED -> false;
    };
  }

  public boolean terminal() {
    return this == FINISHED || this == ABANDONED;
  }

  public boolean active() {
    return this == RUNNING || this == PAUSED;
  }
}
