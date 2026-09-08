package com.shangan.nag.domain;

/** 催办状态机：PENDING → DELIVERED → RESPONDED | EXPIRED。 */
public enum NagStatus {
  PENDING,
  DELIVERED,
  RESPONDED,
  EXPIRED
}
