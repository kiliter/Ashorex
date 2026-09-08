package com.shangan.supervision.domain;

import java.time.Instant;

/** 督学绑定聚合；归档后不再参与权限判定与督学端列表。 */
public record Supervision(
    String id,
    String learnerUserId,
    String supervisorUserId,
    SupervisionKind kind,
    boolean canView,
    boolean canNag,
    boolean canEditGoal,
    boolean canAddTodo,
    Instant archivedAt) {

  public boolean active() {
    return archivedAt == null;
  }

  /** 判断该绑定是否授予指定权限；归档绑定一律不授权。 */
  public boolean allows(SupervisionPermission permission) {
    if (!active()) {
      return false;
    }
    return switch (permission) {
      case VIEW -> canView;
      case NAG -> canNag;
      case EDIT_GOAL -> canEditGoal;
      case ADD_TODO -> canAddTodo;
    };
  }
}
