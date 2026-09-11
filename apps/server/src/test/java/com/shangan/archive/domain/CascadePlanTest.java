package com.shangan.archive.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 级联删除顺序测试。
 *
 * <p>顺序是 Spec 11.4 的硬编码知识，任何顺序变化都必须先改规范，因此这里做逐项精确断言。
 */
class CascadePlanTest {

  @Test
  @DisplayName("课程级联顺序必须逐项与规范一致：先子表流水，最后课程本身")
  void 课程级联顺序固定() {
    assertThat(CascadePlan.courseOrder())
        .containsExactly(
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

  @Test
  @DisplayName("用户级联顺序必须逐项与规范一致：账号行最后删除")
  void 用户级联顺序固定() {
    assertThat(CascadePlan.userOrder())
        .containsExactly(
            "todo_attachments",
            "diagnostic_log_uploads",
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

  @Test
  @DisplayName("子表一定排在其父表之前，避免出现外键悬挂")
  void 子表先于父表() {
    List<String> courseOrder = CascadePlan.courseOrder();
    assertThat(courseOrder.indexOf("learning_resources"))
        .isLessThan(courseOrder.indexOf("courses"));
    assertThat(courseOrder.indexOf("todo_attachments")).isLessThan(courseOrder.indexOf("todos"));
    assertThat(courseOrder.indexOf("todo_progress_events"))
        .isLessThan(courseOrder.indexOf("todos"));

    List<String> userOrder = CascadePlan.userOrder();
    assertThat(userOrder.indexOf("nag_deliveries")).isLessThan(userOrder.indexOf("nags"));
    assertThat(userOrder.indexOf("user_roles")).isLessThan(userOrder.indexOf("users"));
    assertThat(userOrder.indexOf("refresh_tokens")).isLessThan(userOrder.indexOf("users"));
    assertThat(userOrder.getLast()).isEqualTo("users");
  }

  @Test
  @DisplayName("预检清单的总行数等于各步骤行数之和")
  void 总行数为各步骤之和() {
    CascadePlan plan =
        new CascadePlan(
            ArchivableEntityType.COURSE,
            "course-1",
            "考研数学强化",
            List.of(
                new CascadePlan.Step(1, "todo_attachments", 3, "附件行与磁盘文件"),
                new CascadePlan.Step(2, "todos", 12, "含历史日期的待办"),
                new CascadePlan.Step(3, "courses", 1, "课程")),
            2048L,
            2,
            600_000L);

    assertThat(plan.totalRows()).isEqualTo(16);
    assertThat(plan.entityLabel()).isEqualTo("考研数学强化");
  }
}
