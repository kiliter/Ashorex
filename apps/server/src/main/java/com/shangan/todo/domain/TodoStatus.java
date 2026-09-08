package com.shangan.todo.domain;

/**
 * Todo 状态机：{@code TODO → IN_PROGRESS → DONE}。
 *
 * <p>没有 ABANDONED、没有 CLOSED_WITH_DEBT、没有日终自动终态。未完成项在历史日期保持原状， 由「未完成汇总」跨日期聚合。
 */
public enum TodoStatus {
  TODO,
  IN_PROGRESS,
  DONE
}
