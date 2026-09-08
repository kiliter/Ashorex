package com.shangan.goal.domain;

/** 目标紧急度分档：30 天内 URGENT，60 天内 SOON，其余 NORMAL，已过期 EXPIRED。 */
public enum GoalUrgency {
  NORMAL,
  SOON,
  URGENT,
  EXPIRED
}
