package com.shangan.nag.domain;

/**
 * 催办来源。
 *
 * <p>只有 {@code AUTO} 参与 {@code (user, local_date, level)} 幂等键；手动与督学催办不参与幂等键， 但同样计入每日上限（见
 * ADR-0026、ADR-0028）。
 */
public enum NagTrigger {
  AUTO,
  MANUAL,
  SUPERVISOR
}
