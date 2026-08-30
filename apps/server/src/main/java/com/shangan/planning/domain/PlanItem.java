package com.shangan.planning.domain;

import java.time.Instant;

/** 每日计划任务快照，VIDEO 完成需要观看与必答题两个组成部分。 */
public record PlanItem(
    String id,
    String planId,
    String itemType,
    String title,
    String mediaItemId,
    String debtId,
    long plannedSeconds,
    long completedSeconds,
    boolean watchCompleted,
    boolean quizRequired,
    boolean quizCompleted,
    String status,
    int sortOrder,
    Instant completedAt) {

  public boolean complete() {
    return switch (itemType) {
      case "VIDEO" -> watchCompleted && (!quizRequired || quizCompleted);
      case "FOCUS", "QUIZ", "DEBT_REPAYMENT" -> completedSeconds >= plannedSeconds;
      default -> false;
    };
  }
}
