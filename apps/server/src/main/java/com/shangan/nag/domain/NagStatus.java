package com.shangan.nag.domain;

/** 催办状态机：投递、回应、过期；全部失败的 PENDING 可由管理员取消。 */
public enum NagStatus {
  PENDING,
  DELIVERED,
  RESPONDED,
  EXPIRED,
  /** 管理员终止全部失败的投递，保留历史记录。 */
  CANCELLED
}
