package com.shangan.todo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 进度上报纯规则测试。
 *
 * <p>覆盖位置单调、页码单调、前台时长累计口径，以及「position / duration * 1000 >= target」的达标边界。
 */
class TodoProgressPolicyTest {

  private static LearningResource video(long durationMs) {
    return new LearningResource(
        "resource-1",
        "course-1",
        ResourceType.VIDEO,
        "第 1 讲",
        1,
        durationMs,
        null,
        "emby:1",
        "path-sha256-v1:abc",
        true,
        CatalogStatus.ACTIVE,
        null);
  }

  private static LearningResource document(int pageCount) {
    return new LearningResource(
        "resource-2",
        "course-1",
        ResourceType.DOCUMENT,
        "讲义",
        2,
        null,
        pageCount,
        "emby:2",
        null,
        true,
        CatalogStatus.ACTIVE,
        null);
  }

  private static Todo courseTodo(int targetPermille) {
    return new Todo(
        "todo-1",
        "user-1",
        java.time.LocalDate.of(2026, 9, 7),
        TodoType.COURSE,
        "第 1 讲",
        "",
        0,
        TodoStatus.TODO,
        "resource-1",
        targetPermille,
        0L,
        0,
        0L,
        null,
        FocusState.IDLE,
        null,
        0L,
        false,
        null,
        false,
        null,
        null,
        0L);
  }

  @Test
  @DisplayName("上报位置只能前进：更小的位置与负数被忽略，更大的位置生效")
  void 位置单调不回退() {
    assertThat(TodoPolicy.advancePosition(60_000L, 30_000L)).isEqualTo(60_000L);
    assertThat(TodoPolicy.advancePosition(60_000L, 60_000L)).isEqualTo(60_000L);
    assertThat(TodoPolicy.advancePosition(60_000L, 90_000L)).isEqualTo(90_000L);
    assertThat(TodoPolicy.advancePosition(60_000L, -1L)).isEqualTo(60_000L);
    assertThat(TodoPolicy.advancePosition(60_000L, null)).isEqualTo(60_000L);
  }

  @Test
  @DisplayName("材料页码同样单调不回退")
  void 页码单调不回退() {
    assertThat(TodoPolicy.advancePage(12, 5)).isEqualTo(12);
    assertThat(TodoPolicy.advancePage(12, 20)).isEqualTo(20);
    assertThat(TodoPolicy.advancePage(12, -3)).isEqualTo(12);
    assertThat(TodoPolicy.advancePage(12, null)).isEqualTo(12);
  }

  @Test
  @DisplayName("只有前台增量计入累计时长，后台与非正增量都不累计")
  void 只累计前台时长增量() {
    assertThat(TodoPolicy.accumulate(1_000L, 15_000L, true)).isEqualTo(16_000L);
    assertThat(TodoPolicy.accumulate(1_000L, 15_000L, false)).isEqualTo(1_000L);
    assertThat(TodoPolicy.accumulate(1_000L, 0L, true)).isEqualTo(1_000L);
    assertThat(TodoPolicy.accumulate(1_000L, -5_000L, true)).isEqualTo(1_000L);
  }

  @Test
  @DisplayName("倍速播放不改变时长口径：累计只认真实经过的毫秒增量")
  void 倍速不改变时长口径() {
    // 2 倍速看完 30 秒素材，真实经过时间只有 15 秒，客户端上报的增量即 15 秒。
    long accumulated = TodoPolicy.accumulate(0L, 15_000L, true);
    assertThat(accumulated).isEqualTo(15_000L);
    assertThat(TodoPolicy.advancePosition(0L, 30_000L)).isEqualTo(30_000L);
  }

  @Test
  @DisplayName("视频千分比按 position / duration * 1000 计算并封顶 1000")
  void 视频千分比计算与封顶() {
    LearningResource resource = video(100_000L);
    assertThat(resource.progressPermille(0L, 0)).isZero();
    assertThat(resource.progressPermille(49_900L, 0)).isEqualTo(499);
    assertThat(resource.progressPermille(50_000L, 0)).isEqualTo(500);
    assertThat(resource.progressPermille(200_000L, 0)).isEqualTo(1000);
  }

  @Test
  @DisplayName("目标进度 500 时：499 未达标、500 与 501 达标")
  void 达标边界为目标千分比本身() {
    Todo todo = courseTodo(500);
    assertThat(todo.reachedTarget(499)).isFalse();
    assertThat(todo.reachedTarget(500)).isTrue();
    assertThat(todo.reachedTarget(501)).isTrue();
  }

  @Test
  @DisplayName("没有目标进度的待办永不判定达标")
  void 无目标进度不判定达标() {
    Todo todo = courseTodo(500);
    Todo withoutTarget =
        new Todo(
            todo.id(),
            todo.userId(),
            todo.localDate(),
            todo.todoType(),
            todo.title(),
            todo.note(),
            todo.sortOrder(),
            todo.status(),
            todo.resourceId(),
            null,
            todo.progressPositionMs(),
            todo.progressPage(),
            todo.watchedMs(),
            todo.plannedSeconds(),
            todo.focusState(),
            todo.focusStartedAt(),
            todo.focusedMs(),
            todo.requireEvidence(),
            todo.completedAt(),
            todo.backfilled(),
            todo.backfillNote(),
            todo.supervisorUserIdSnapshot(),
            todo.focusAttemptBaseMs());
    assertThat(withoutTarget.reachedTarget(1000)).isFalse();
  }

  @Test
  @DisplayName("缺少总量的资源不可度量，千分比恒为 0")
  void 缺少总量时千分比为零() {
    assertThat(video(0L).measurable()).isFalse();
    assertThat(video(0L).progressPermille(10_000L, 0)).isZero();
    assertThat(document(0).progressPermille(0L, 5)).isZero();
    assertThat(document(10).progressPermille(0L, 5)).isEqualTo(500);
  }
}
