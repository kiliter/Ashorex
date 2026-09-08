package com.shangan.todo.domain;

/**
 * 删除原因标签。
 *
 * <p>删除任意 Todo 都必须填写标签与说明，用于统计与督学提醒（见 Spec 7.4）。 {@code GAVE_UP} 会立即通知主督学人。
 */
public enum DeletionReasonTag {
  TOO_MANY_PLANNED,
  TEMP_BUSY,
  ADDED_BY_MISTAKE,
  SWITCHED_TO_OTHER,
  GAVE_UP
}
