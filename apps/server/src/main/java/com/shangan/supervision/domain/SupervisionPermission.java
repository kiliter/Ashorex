package com.shangan.supervision.domain;

/**
 * 督学权限开关。
 *
 * <p>V2 默认只开 {@code VIEW} 与 {@code NAG}；{@code EDIT_GOAL} 与 {@code ADD_TODO} 落库但默认关闭，
 * 避免督学端变成第二个学习端（见 ADR-0028）。
 */
public enum SupervisionPermission {
  VIEW,
  NAG,
  EDIT_GOAL,
  ADD_TODO
}
