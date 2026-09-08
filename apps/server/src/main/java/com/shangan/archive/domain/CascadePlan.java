package com.shangan.archive.domain;

import java.util.List;

/**
 * 级联删除方案。
 *
 * <p>删除顺序是硬编码知识（Spec 11.4），必须与孤儿自检项同步维护。方案里的顺序即执行顺序， 任一步失败整体回滚。
 */
public record CascadePlan(
    ArchivableEntityType entityType,
    String entityId,
    String entityLabel,
    List<Step> steps,
    long attachmentBytes,
    int affectedUserCount,
    long affectedWatchedMs) {

  /** 课程的固定删除顺序。 */
  public static List<String> courseOrder() {
    return List.of(
        "todo_attachments",
        "todo_progress_events",
        "todo_deletions",
        "todos",
        "lesson_watch_states",
        "course_genres",
        "course_tags",
        "course_people",
        "resource_source_mappings",
        "learning_resources",
        "courses");
  }

  /** 用户的固定删除顺序。 */
  public static List<String> userOrder() {
    return List.of(
        "todo_attachments",
        "todo_progress_events",
        "todos",
        "todo_deletions",
        "lesson_watch_states",
        "exam_goals",
        "nag_deliveries",
        "nags",
        "nag_policies",
        "supervisions",
        "user_presence",
        "user_bark_settings",
        "refresh_tokens",
        "user_roles",
        "users");
  }

  public int totalRows() {
    return steps.stream().mapToInt(Step::rowCount).sum();
  }

  /** 一个删除步骤：表名、预计行数与说明。 */
  public record Step(int order, String table, int rowCount, String description) {}
}
