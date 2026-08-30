package com.shangan.planning.domain;

/** 每日计划不可逆状态。 */
public enum PlanStatus {
  DRAFT,
  LOCKED,
  COMPLETED,
  ABANDONED,
  CLOSED_WITH_DEBT
}
