package com.shangan.todo;

import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.domain.TodoType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 测试用 Todo 构造器。
 *
 * <p>Todo 是 23 个字段的 record，测试里逐个写实参会掩盖真正被断言的字段，因此集中提供可链式覆盖的构造器。
 */
public final class TodoFixtures {

  public static final String USER_ID = "user-1";
  public static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

  private String id = "todo-1";
  private String userId = USER_ID;
  private LocalDate localDate = TODAY;
  private TodoType todoType = TodoType.TASK;
  private String title = "背 50 个单词";
  private String note = "";
  private int sortOrder = 0;
  private TodoStatus status = TodoStatus.TODO;
  private String resourceId;
  private Integer targetProgressPermille;
  private long progressPositionMs;
  private int progressPage;
  private long watchedMs;
  private Integer plannedSeconds;
  private FocusState focusState = FocusState.IDLE;
  private Instant focusStartedAt;
  private long focusedMs;
  private boolean requireEvidence;
  private Instant completedAt;
  private boolean backfilled;
  private String backfillNote;
  private String supervisorUserIdSnapshot;

  private TodoFixtures() {}

  /**
   * 催办策略夹具，只暴露删除流程真正关心的三个督学提醒开关。
   *
   * <p>其余字段取与内置默认一致的值，避免测试因为无关字段变化而失败。
   */
  public static EffectiveNagPolicy nagPolicy(
      boolean notifyOnBulkDelete, boolean notifyOnGaveUp, boolean notifyOnHalfDoneDelete) {
    return new EffectiveNagPolicy(
        5,
        150,
        60,
        90,
        60,
        3,
        10,
        LocalTime.of(23, 30),
        LocalTime.of(7, 0),
        1,
        5,
        true,
        true,
        "{{user}} 今天还有 {{pending}} 项未完成。",
        notifyOnBulkDelete,
        notifyOnGaveUp,
        notifyOnHalfDoneDelete);
  }

  /** 三个督学提醒开关全开的策略，代表后台默认配置。 */
  public static EffectiveNagPolicy nagPolicyAllEnabled() {
    return nagPolicy(true, true, true);
  }

  /** 指定总时长的视频课时，用于按 `position / duration` 换算真实进度。 */
  public static LearningResource videoResource(long durationMs) {
    return new LearningResource(
        "resource-1",
        "course-1",
        ResourceType.VIDEO,
        "第 1 讲",
        0,
        durationMs,
        null,
        "emby-1",
        "fingerprint-1",
        true,
        CatalogStatus.ACTIVE,
        null);
  }

  /** 待办事项类型的默认 Todo。 */
  public static TodoFixtures task() {
    return new TodoFixtures();
  }

  /** 课程类型的默认 Todo：带资源与目标进度。 */
  public static TodoFixtures course() {
    TodoFixtures fixtures = new TodoFixtures();
    fixtures.todoType = TodoType.COURSE;
    fixtures.title = "第 1 讲";
    fixtures.resourceId = "resource-1";
    fixtures.targetProgressPermille = 1000;
    return fixtures;
  }

  /** 专注类型的默认 Todo：25 分钟倒计时。 */
  public static TodoFixtures focus() {
    TodoFixtures fixtures = new TodoFixtures();
    fixtures.todoType = TodoType.FOCUS;
    fixtures.title = "专注刷题";
    fixtures.plannedSeconds = 1_500;
    return fixtures;
  }

  public TodoFixtures id(String value) {
    this.id = value;
    return this;
  }

  public TodoFixtures userId(String value) {
    this.userId = value;
    return this;
  }

  public TodoFixtures localDate(LocalDate value) {
    this.localDate = value;
    return this;
  }

  public TodoFixtures status(TodoStatus value) {
    this.status = value;
    return this;
  }

  public TodoFixtures resourceId(String value) {
    this.resourceId = value;
    return this;
  }

  public TodoFixtures targetProgressPermille(Integer value) {
    this.targetProgressPermille = value;
    return this;
  }

  public TodoFixtures progressPositionMs(long value) {
    this.progressPositionMs = value;
    return this;
  }

  public TodoFixtures progressPage(int value) {
    this.progressPage = value;
    return this;
  }

  public TodoFixtures watchedMs(long value) {
    this.watchedMs = value;
    return this;
  }

  public TodoFixtures focusState(FocusState value) {
    this.focusState = value;
    return this;
  }

  public TodoFixtures focusStartedAt(Instant value) {
    this.focusStartedAt = value;
    return this;
  }

  public TodoFixtures focusedMs(long value) {
    this.focusedMs = value;
    return this;
  }

  public TodoFixtures requireEvidence(boolean value) {
    this.requireEvidence = value;
    return this;
  }

  public TodoFixtures backfilled(boolean value) {
    this.backfilled = value;
    return this;
  }

  public TodoFixtures supervisorUserIdSnapshot(String value) {
    this.supervisorUserIdSnapshot = value;
    return this;
  }

  private long focusAttemptBaseMs;

  /** 为多轮计时测试设置历史累计基线。 */
  public TodoFixtures focusAttemptBaseMs(long value) {
    this.focusAttemptBaseMs = value;
    return this;
  }

  public Todo build() {
    return new Todo(
        id,
        userId,
        localDate,
        todoType,
        title,
        note,
        sortOrder,
        status,
        resourceId,
        targetProgressPermille,
        progressPositionMs,
        progressPage,
        watchedMs,
        plannedSeconds,
        focusState,
        focusStartedAt,
        focusedMs,
        requireEvidence,
        completedAt,
        backfilled,
        backfillNote,
        supervisorUserIdSnapshot,
        focusAttemptBaseMs);
  }
}
