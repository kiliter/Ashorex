package com.shangan.stats.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.stats.application.StatsService.DayStats;
import com.shangan.stats.application.StatsService.StatsView;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 统计聚合口径：按用户时区归集、补记单列、专注两种终态与排行排序。 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

  private static final String USER_ID = TodoFixtures.USER_ID;
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
  private static final User USER =
      new User(
          USER_ID,
          "demo",
          "hash",
          "小明",
          "Asia/Shanghai",
          UserStatus.ACTIVE,
          null,
          Set.of(UserRole.LEARNER));

  @Mock private TodoRepository todos;
  @Mock private CourseRepository courses;
  @Mock private UserTimeService userTime;

  private StatsService service;

  @BeforeEach
  void setUp() {
    service = new StatsService(todos, courses, userTime);
  }

  @Test
  @DisplayName("日视图：总数、完成数、补记数与两类时长分别统计")
  void 日视图字段完整() {
    stubDay(TODAY);
    stubZone();
    when(todos.findByUserBetween(USER_ID, TODAY, TODAY))
        .thenReturn(
            List.of(
                TodoFixtures.task().id("t1").status(TodoStatus.DONE).build(),
                TodoFixtures.task().id("t2").build(),
                TodoFixtures.task().id("t3").status(TodoStatus.DONE).backfilled(true).build()));
    when(todos.aggregateDurations(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(
            List.of(
                new TodoRepository.DurationBucket(instantAt(TODAY, 10), 600_000L, 0L),
                new TodoRepository.DurationBucket(instantAt(TODAY, 15), 300_000L, 1_500_000L)));
    when(userTime.localDateOf(USER, instantAt(TODAY, 10))).thenReturn(TODAY);
    when(userTime.localDateOf(USER, instantAt(TODAY, 15))).thenReturn(TODAY);
    when(todos.countNoteTags(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of(new TodoRepository.TagCount(NoteTag.NEED_REVIEW, 2)));
    when(todos.findDeletions(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());

    StatsView view = service.stats(USER_ID, "DAY", null);

    assertThat(view.range()).isEqualTo("DAY");
    assertThat(view.start()).isEqualTo(TODAY);
    assertThat(view.end()).isEqualTo(TODAY);
    assertThat(view.totalTodos()).isEqualTo(3);
    assertThat(view.doneTodos()).isEqualTo(2);
    assertThat(view.backfilledTodos()).isEqualTo(1);
    assertThat(view.watchedMs()).isEqualTo(900_000L);
    assertThat(view.focusedMs()).isEqualTo(1_500_000L);
    assertThat(view.noteTags()).containsExactly(new StatsService.TagCount(NoteTag.NEED_REVIEW, 2));
    assertThat(view.days()).hasSize(1);
  }

  @Test
  @DisplayName("补记完成单列统计，且本身不产生任何时长")
  void 补记不计入时长() {
    stubDay(TODAY);
    when(todos.findByUserBetween(USER_ID, TODAY, TODAY))
        .thenReturn(List.of(TodoFixtures.task().status(TodoStatus.DONE).backfilled(true).build()));
    when(todos.aggregateDurations(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.countNoteTags(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.findDeletions(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());

    StatsView view = service.stats(USER_ID, "DAY", null);

    assertThat(view.backfilledTodos()).isEqualTo(1);
    assertThat(view.doneTodos()).isEqualTo(1);
    assertThat(view.watchedMs()).isZero();
    assertThat(view.focusedMs()).isZero();
    assertThat(view.days())
        .singleElement()
        .satisfies(
            day -> {
              assertThat(day.backfilled()).isEqualTo(1);
              assertThat(day.watchedMs()).isZero();
            });
  }

  @Test
  @DisplayName("专注两种终态分别计数，用于计算专注完成率")
  void 专注两种终态分别计数() {
    stubDay(TODAY);
    when(todos.findByUserBetween(USER_ID, TODAY, TODAY))
        .thenReturn(
            List.of(
                TodoFixtures.focus()
                    .id("f1")
                    .focusState(FocusState.FINISHED)
                    .status(TodoStatus.DONE)
                    .build(),
                TodoFixtures.focus().id("f2").focusState(FocusState.ABANDONED).build(),
                TodoFixtures.focus().id("f3").focusState(FocusState.ABANDONED).build()));
    when(todos.aggregateDurations(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.countNoteTags(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.findDeletions(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());

    StatsView view = service.stats(USER_ID, "DAY", null);

    assertThat(view.focusFinished()).isEqualTo(1);
    assertThat(view.focusAbandoned()).isEqualTo(2);
  }

  @Test
  @DisplayName("时长按用户时区归集：UTC 次日凌晨的事件仍算作东八区当天")
  void 按用户时区归集时长() {
    stubDay(TODAY);
    stubZone();
    when(todos.findByUserBetween(USER_ID, TODAY, TODAY)).thenReturn(List.of());
    Instant lateNight = Instant.parse("2026-09-07T15:30:00Z");
    when(todos.aggregateDurations(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of(new TodoRepository.DurationBucket(lateNight, 120_000L, 0L)));
    when(userTime.localDateOf(USER, lateNight)).thenReturn(TODAY);
    when(todos.countNoteTags(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.findDeletions(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());

    StatsView view = service.stats(USER_ID, "DAY", null);

    assertThat(view.days())
        .singleElement()
        .satisfies(day -> assertThat(day.watchedMs()).isEqualTo(120_000L));
    assertThat(view.hours())
        .singleElement()
        .satisfies(
            slot -> {
              assertThat(slot.hour()).isEqualTo(23);
              assertThat(slot.watchedMs()).isEqualTo(120_000L);
            });
  }

  @Test
  @DisplayName("周视图从周一开始到周日结束，逐日占位不缺天")
  void 周视图区间与占位() {
    when(userTime.requireUser(USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    LocalDate monday = LocalDate.of(2026, 9, 7);
    LocalDate sunday = LocalDate.of(2026, 9, 13);
    when(userTime.startOfDay(USER, monday)).thenReturn(startOfDay(monday));
    when(userTime.endOfDayExclusive(USER, sunday)).thenReturn(startOfDay(sunday.plusDays(1)));
    when(todos.findByUserBetween(USER_ID, monday, sunday)).thenReturn(List.of());
    when(todos.aggregateDurations(USER_ID, startOfDay(monday), startOfDay(sunday.plusDays(1))))
        .thenReturn(List.of());
    when(todos.countNoteTags(USER_ID, startOfDay(monday), startOfDay(sunday.plusDays(1))))
        .thenReturn(List.of());
    when(todos.findDeletions(USER_ID, startOfDay(monday), startOfDay(sunday.plusDays(1))))
        .thenReturn(List.of());

    StatsView view = service.stats(USER_ID, "WEEK", "2026-09-09");

    assertThat(view.range()).isEqualTo("WEEK");
    assertThat(view.start()).isEqualTo(monday);
    assertThat(view.end()).isEqualTo(sunday);
    assertThat(view.days())
        .extracting(DayStats::date)
        .hasSize(7)
        .startsWith(monday)
        .endsWith(sunday);
    // 时段分布只在日视图产出。
    assertThat(view.hours()).isEmpty();
  }

  @Test
  @DisplayName("月视图覆盖整月天数")
  void 月视图覆盖整月() {
    when(userTime.requireUser(USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);
    LocalDate first = LocalDate.of(2026, 9, 1);
    LocalDate last = LocalDate.of(2026, 9, 30);
    when(userTime.startOfDay(USER, first)).thenReturn(startOfDay(first));
    when(userTime.endOfDayExclusive(USER, last)).thenReturn(startOfDay(last.plusDays(1)));
    when(todos.findByUserBetween(USER_ID, first, last)).thenReturn(List.of());
    when(todos.aggregateDurations(USER_ID, startOfDay(first), startOfDay(last.plusDays(1))))
        .thenReturn(List.of());
    when(todos.countNoteTags(USER_ID, startOfDay(first), startOfDay(last.plusDays(1))))
        .thenReturn(List.of());
    when(todos.findDeletions(USER_ID, startOfDay(first), startOfDay(last.plusDays(1))))
        .thenReturn(List.of());

    StatsView view = service.stats(USER_ID, "MONTH", "2026-09-20");

    assertThat(view.range()).isEqualTo("MONTH");
    assertThat(view.days()).hasSize(30);
  }

  @Test
  @DisplayName("课程排行按观看时长降序，并统计课时数与人物流派")
  void 课程排行按时长降序() {
    stubDay(TODAY);
    Todo more = TodoFixtures.course().id("t1").resourceId("res-1").watchedMs(900_000L).build();
    Todo less = TodoFixtures.course().id("t2").resourceId("res-2").watchedMs(300_000L).build();
    Todo zero = TodoFixtures.course().id("t3").resourceId("res-3").watchedMs(0L).build();
    when(todos.findByUserBetween(USER_ID, TODAY, TODAY)).thenReturn(List.of(more, less, zero));
    when(todos.aggregateResourceWatchedMs(
            USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(
            List.of(
                new TodoRepository.ResourceWatchBucket("res-1", 900_000L),
                new TodoRepository.ResourceWatchBucket("res-2", 300_000L)));
    stubResource("res-1", "course-1", "英语长难句");
    stubResource("res-2", "course-2", "数学强化");
    when(courses.peopleOf("course-1"))
        .thenReturn(
            List.of(
                new ResourceMetadata.Person("刘老师", "Actor"),
                new ResourceMetadata.Person("刘老师", "Director")));
    when(courses.peopleOf("course-2")).thenReturn(List.of());
    when(courses.genresOf("course-1")).thenReturn(List.of("英语"));
    when(courses.genresOf("course-2")).thenReturn(List.of("数学"));
    when(todos.aggregateDurations(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.countNoteTags(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.findDeletions(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());

    StatsView view = service.stats(USER_ID, "DAY", null);

    assertThat(view.rankings().courses())
        .extracting(StatsService.RankingEntry::name, StatsService.RankingEntry::watchedMs)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("英语长难句", 900_000L),
            org.assertj.core.groups.Tuple.tuple("数学强化", 300_000L));
    // 同一人物兼任两个角色，不能把同一课时的观看时间重复累计。
    assertThat(view.rankings().people())
        .extracting(StatsService.RankingEntry::name, StatsService.RankingEntry::watchedMs)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("刘老师", 900_000L));
    assertThat(view.rankings().genres())
        .extracting(StatsService.RankingEntry::name)
        .containsExactly("英语", "数学");
  }

  @Test
  @DisplayName("顺延后历史日无Todo也保留观看排行，同名课程不混为一个身份")
  void 顺延后按事件窗口保留同名课程排行() {
    stubDay(TODAY);
    when(todos.aggregateResourceWatchedMs(
            USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(
            List.of(
                new TodoRepository.ResourceWatchBucket("r-a", 120_000L),
                new TodoRepository.ResourceWatchBucket("r-b", 60_000L)));
    stubResource("r-a", "c-a", "同名课程");
    stubResource("r-b", "c-b", "同名课程");

    StatsView result = service.stats(USER_ID, "DAY", null);

    assertThat(result.totalTodos()).isZero();
    assertThat(result.rankings().courses())
        .extracting(
            StatsService.RankingEntry::name,
            StatsService.RankingEntry::watchedMs,
            StatsService.RankingEntry::lessonCount)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("同名课程", 120_000L, 1),
            org.assertj.core.groups.Tuple.tuple("同名课程", 60_000L, 1));
  }

  @Test
  @DisplayName("删除原因按次数降序聚合")
  void 删除原因按次数降序() {
    stubDay(TODAY);
    when(todos.findByUserBetween(USER_ID, TODAY, TODAY)).thenReturn(List.of());
    when(todos.aggregateDurations(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.countNoteTags(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(List.of());
    when(todos.findDeletions(USER_ID, startOfDay(TODAY), startOfDay(TODAY.plusDays(1))))
        .thenReturn(
            List.of(
                deletion(com.shangan.todo.domain.DeletionReasonTag.TEMP_BUSY),
                deletion(com.shangan.todo.domain.DeletionReasonTag.GAVE_UP),
                deletion(com.shangan.todo.domain.DeletionReasonTag.GAVE_UP)));

    StatsView view = service.stats(USER_ID, "DAY", null);

    assertThat(view.deletions())
        .extracting(
            StatsService.DeletionReasonCount::reasonTag, StatsService.DeletionReasonCount::count)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("GAVE_UP", 2),
            org.assertj.core.groups.Tuple.tuple("TEMP_BUSY", 1));
  }

  @Test
  @DisplayName("非法日期返回 STATS_DATE_INVALID")
  void 非法日期被拒绝() {
    when(userTime.requireUser(USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(TODAY);

    assertThatThrownBy(() -> service.stats(USER_ID, "DAY", "2026/09/07"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("STATS_DATE_INVALID");
  }

  private void stubDay(LocalDate date) {
    when(userTime.requireUser(USER_ID)).thenReturn(USER);
    when(userTime.today(USER)).thenReturn(date);
    when(userTime.startOfDay(USER, date)).thenReturn(startOfDay(date));
    when(userTime.endOfDayExclusive(USER, date)).thenReturn(startOfDay(date.plusDays(1)));
  }

  /** 只有产出时段分布（日视图且有时长流水）时才需要时区。 */
  private void stubZone() {
    when(userTime.zoneOf(USER)).thenReturn(ZONE);
  }

  private void stubResource(String resourceId, String courseId, String courseTitle) {
    when(courses.findResourceById(resourceId))
        .thenReturn(
            Optional.of(
                new com.shangan.catalog.domain.LearningResource(
                    resourceId,
                    courseId,
                    com.shangan.catalog.domain.ResourceType.VIDEO,
                    "课时",
                    1,
                    600_000L,
                    null,
                    "emby:" + resourceId,
                    null,
                    true,
                    com.shangan.catalog.domain.CatalogStatus.ACTIVE,
                    null)));
    when(courses.findById(courseId))
        .thenReturn(
            Optional.of(
                new Course(
                    courseId,
                    "EMBY",
                    "emby-course-" + courseId,
                    courseTitle,
                    "",
                    null,
                    0,
                    com.shangan.catalog.domain.CatalogStatus.ACTIVE,
                    false,
                    null,
                    null,
                    null)));
  }

  private static TodoRepository.Deletion deletion(
      com.shangan.todo.domain.DeletionReasonTag reasonTag) {
    return new TodoRepository.Deletion(
        "deletion-" + reasonTag.name(),
        USER_ID,
        "todo-x",
        TodoType.TASK,
        TODAY,
        "标题",
        null,
        "{}",
        reasonTag,
        "原因说明",
        null,
        startOfDay(TODAY));
  }

  private static Instant startOfDay(LocalDate date) {
    return date.atStartOfDay(ZONE).toInstant();
  }

  private static Instant instantAt(LocalDate date, int hour) {
    return date.atStartOfDay(ZONE).plusHours(hour).toInstant();
  }
}
