package com.shangan.debt.domain;

import java.time.Instant;
import java.time.LocalDate;

/** 可追溯到原任务组成部分的学习欠债。 */
public record LearningDebt(
    String id,
    String userId,
    String sourcePlanItemId,
    String debtType,
    String mediaItemId,
    String title,
    long originalSeconds,
    long remainingSeconds,
    long baselineCompletedSeconds,
    String status,
    String reason,
    LocalDate openedOn,
    Instant paidAt) {}
