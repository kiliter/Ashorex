package com.shangan.stats.application;

import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日 / 周 / 月统计聚合。
 *
 * <p>全部按用户时区归集，实时查询不建物化表。补记完成单列且不计入当日时长；观看与阅读时长按资源类型分列。
 */
@Service
public class StatsService {

  private final TodoRepository todos;
  private final CourseRepository courses;
  private final UserTimeService userTime;

  public StatsService(TodoRepository todos, CourseRepository courses, UserTimeService userTime) {
    this.todos = todos;
    this.courses = courses;
    this.userTime = userTime;
  }

  /** 统计入口；range 为 DAY / WEEK / MONTH。 */
  @Transactional(readOnly = true)
  public StatsView stats(String userId, String range, String date) {
    User user = userTime.requireUser(userId);
    LocalDate today = userTime.today(user);
    LocalDate anchor = parseOr(date, today);
    return switch (range == null ? "DAY" : range.toUpperCase(java.util.Locale.ROOT)) {
      case "WEEK" ->
          build(
              user,
              anchor.minusDays(anchor.getDayOfWeek().getValue() - 1L),
              anchor.minusDays(anchor.getDayOfWeek().getValue() - 1L).plusDays(6),
              "WEEK");
      case "MONTH" -> {
        YearMonth month = YearMonth.from(anchor);
        yield build(user, month.atDay(1), month.atEndOfMonth(), "MONTH");
      }
      default -> build(user, anchor, anchor, "DAY");
    };
  }

  private StatsView build(User user, LocalDate start, LocalDate end, String range) {
    Instant from = userTime.startOfDay(user, start);
    Instant to = userTime.endOfDayExclusive(user, end);
    List<Todo> items = todos.findByUserBetween(user.id(), start, end);
    List<TodoRepository.DurationBucket> buckets = todos.aggregateDurations(user.id(), from, to);

    Map<LocalDate, DayStats> byDate = new LinkedHashMap<>();
    for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
      byDate.put(cursor, DayStats.empty(cursor));
    }
    for (Todo todo : items) {
      byDate.computeIfPresent(
          todo.localDate(),
          (key, value) -> value.withTodo(todo.done(), todo.backfilled(), todo.todoType()));
    }
    for (TodoRepository.DurationBucket bucket : buckets) {
      LocalDate day = userTime.localDateOf(user, bucket.occurredAt());
      byDate.computeIfPresent(
          day, (key, value) -> value.withDuration(bucket.watchedMs(), bucket.focusedMs()));
    }
    Map<Integer, long[]> hourly = new LinkedHashMap<>();
    if ("DAY".equals(range)) {
      for (TodoRepository.DurationBucket bucket : buckets) {
        int hour =
            java.time.LocalDateTime.ofInstant(bucket.occurredAt(), userTime.zoneOf(user)).getHour();
        long[] slot = hourly.computeIfAbsent(hour, key -> new long[2]);
        slot[0] += bucket.watchedMs();
        slot[1] += bucket.focusedMs();
      }
    }

    long watchedMs = buckets.stream().mapToLong(TodoRepository.DurationBucket::watchedMs).sum();
    long focusedMs = buckets.stream().mapToLong(TodoRepository.DurationBucket::focusedMs).sum();
    int total = items.size();
    int done = (int) items.stream().filter(Todo::done).count();
    int backfilled = (int) items.stream().filter(Todo::backfilled).count();
    int focusFinished =
        (int) items.stream().filter(todo -> todo.focusState() == FocusState.FINISHED).count();
    int focusAbandoned =
        (int) items.stream().filter(todo -> todo.focusState() == FocusState.ABANDONED).count();

    return new StatsView(
        range,
        start,
        end,
        total,
        done,
        backfilled,
        watchedMs,
        focusedMs,
        focusFinished,
        focusAbandoned,
        List.copyOf(byDate.values()),
        hourlyList(hourly),
        rankings(todos.aggregateResourceWatchedMs(user.id(), from, to)),
        noteTagCounts(user.id(), from, to),
        deletionCounts(user.id(), from, to));
  }

  private List<HourSlot> hourlyList(Map<Integer, long[]> hourly) {
    List<HourSlot> slots = new ArrayList<>();
    hourly.forEach((hour, values) -> slots.add(new HourSlot(hour, values[0], values[1])));
    slots.sort(Comparator.comparingInt(HourSlot::hour));
    return List.copyOf(slots);
  }

  /** 课程、人物、流派排行按窗口内实际观看时长降序，课程身份使用 ID。 */
  private Rankings rankings(List<TodoRepository.ResourceWatchBucket> items) {
    Map<String, Long> byCourse = new LinkedHashMap<>();
    Map<String, Long> byPerson = new LinkedHashMap<>();
    Map<String, Long> byGenre = new LinkedHashMap<>();
    Map<String, Integer> lessonsByCourse = new LinkedHashMap<>();
    Map<String, String> courseNames = new LinkedHashMap<>();
    for (TodoRepository.ResourceWatchBucket todo : items) {
      if (todo.resourceId() == null || todo.watchedMs() <= 0) {
        continue;
      }
      courses
          .findResourceById(todo.resourceId())
          .flatMap(resource -> courses.findById(resource.courseId()))
          .ifPresent(
              course -> {
                byCourse.merge(course.id(), todo.watchedMs(), Long::sum);
                courseNames.put(course.id(), course.title());
                lessonsByCourse.merge(course.id(), 1, Integer::sum);
                courses.peopleOf(course.id()).stream()
                    // 同一人物可以有多个 Emby 角色，人物排行按姓名只计算一次本课时。
                    .map(com.shangan.catalog.domain.ResourceMetadata.Person::name)
                    .distinct()
                    .forEach(name -> byPerson.merge(name, todo.watchedMs(), Long::sum));
                courses
                    .genresOf(course.id())
                    .forEach(genre -> byGenre.merge(genre, todo.watchedMs(), Long::sum));
              });
    }
    return new Rankings(
        toRanking(byCourse, lessonsByCourse).stream()
            .map(
                entry ->
                    new RankingEntry(
                        courseNames.get(entry.name()), entry.watchedMs(), entry.lessonCount()))
            .toList(),
        toRanking(byPerson, Map.of()),
        toRanking(byGenre, Map.of()));
  }

  private List<RankingEntry> toRanking(Map<String, Long> source, Map<String, Integer> counts) {
    List<RankingEntry> entries = new ArrayList<>();
    source.forEach(
        (name, watched) ->
            entries.add(new RankingEntry(name, watched, counts.getOrDefault(name, 0))));
    entries.sort(Comparator.comparingLong(RankingEntry::watchedMs).reversed());
    return List.copyOf(entries);
  }

  private List<TagCount> noteTagCounts(String userId, Instant from, Instant to) {
    List<TagCount> result = new ArrayList<>();
    for (TodoRepository.TagCount count : todos.countNoteTags(userId, from, to)) {
      result.add(new TagCount(count.tag(), count.count()));
    }
    return List.copyOf(result);
  }

  private List<DeletionReasonCount> deletionCounts(String userId, Instant from, Instant to) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    todos
        .findDeletions(userId, from, to)
        .forEach(deletion -> counts.merge(deletion.reasonTag().name(), 1, Integer::sum));
    List<DeletionReasonCount> result = new ArrayList<>();
    counts.forEach((tag, count) -> result.add(new DeletionReasonCount(tag, count)));
    result.sort(Comparator.comparingInt(DeletionReasonCount::count).reversed());
    return List.copyOf(result);
  }

  private LocalDate parseOr(String value, LocalDate fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException exception) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "STATS_DATE_INVALID", "日期格式必须为 YYYY-MM-DD");
    }
  }

  /** 单日统计；热力图分档由客户端按时长与完成情况渲染。 */
  public record DayStats(
      LocalDate date,
      int total,
      int done,
      int backfilled,
      long watchedMs,
      long focusedMs,
      Map<TodoType, Integer> countByType) {

    static DayStats empty(LocalDate date) {
      return new DayStats(date, 0, 0, 0, 0L, 0L, new LinkedHashMap<>());
    }

    DayStats withTodo(boolean done, boolean backfilled, TodoType type) {
      Map<TodoType, Integer> counts = new LinkedHashMap<>(countByType());
      counts.merge(type, 1, Integer::sum);
      return new DayStats(
          date,
          total + 1,
          this.done + (done ? 1 : 0),
          this.backfilled + (backfilled ? 1 : 0),
          watchedMs,
          focusedMs,
          counts);
    }

    DayStats withDuration(long addedWatched, long addedFocused) {
      return new DayStats(
          date,
          total,
          done,
          backfilled,
          watchedMs + addedWatched,
          focusedMs + addedFocused,
          countByType);
    }
  }

  /** 日视图的时段分布。 */
  public record HourSlot(int hour, long watchedMs, long focusedMs) {}

  /** 排行条目；{@code lessonCount} 只在课程排行有意义。 */
  public record RankingEntry(String name, long watchedMs, int lessonCount) {}

  /** 三种排行。 */
  public record Rankings(
      List<RankingEntry> courses, List<RankingEntry> people, List<RankingEntry> genres) {}

  /** 备注标签计数。 */
  public record TagCount(NoteTag tag, int count) {}

  /** 删除原因计数。 */
  public record DeletionReasonCount(String reasonTag, int count) {}

  /** 统计视图。 */
  public record StatsView(
      String range,
      LocalDate start,
      LocalDate end,
      int totalTodos,
      int doneTodos,
      int backfilledTodos,
      long watchedMs,
      long focusedMs,
      int focusFinished,
      int focusAbandoned,
      List<DayStats> days,
      List<HourSlot> hours,
      Rankings rankings,
      List<TagCount> noteTags,
      List<DeletionReasonCount> deletions) {}
}
