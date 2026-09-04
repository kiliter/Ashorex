package com.shangan.planning.domain;

import java.time.Instant;

/** 每日计划任务快照；任务完成判定由 PlanningRepository 的单一 SQL 规则负责。 */
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
    Instant completedAt) {}
