package com.shangan.todo.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Todo 聚合。
 *
 * <p>三种类型共用同一结构，类型专属字段可空，这是刻意的简化（见 ADR-0025）。 进度与完成判定由服务端裁决，客户端只上报观测值。
 */
public record Todo(
    String id,
    String userId,
    LocalDate localDate,
    TodoType todoType,
    String title,
    String note,
    int sortOrder,
    TodoStatus status,
    String resourceId,
    Integer targetProgressPermille,
    long progressPositionMs,
    int progressPage,
    long watchedMs,
    Integer plannedSeconds,
    FocusState focusState,
    Instant focusStartedAt,
    long focusedMs,
    boolean requireEvidence,
    Instant completedAt,
    boolean backfilled,
    String backfillNote,
    String supervisorUserIdSnapshot,
    long focusAttemptBaseMs) {

  /** 本轮已落账时长；历史轮次只计入总时长，不抵扣新一轮倒计时。 */
  public long focusAttemptMs() {
    return Math.max(0, focusedMs - focusAttemptBaseMs);
  }

  public boolean done() {
    return status == TodoStatus.DONE;
  }

  public boolean pending() {
    return status != TodoStatus.DONE;
  }

  /** 课程 Todo 是否已达到目标进度；总量缺失时不判定达标。 */
  public boolean reachedTarget(int currentPermille) {
    return targetProgressPermille != null && currentPermille >= targetProgressPermille;
  }
}
