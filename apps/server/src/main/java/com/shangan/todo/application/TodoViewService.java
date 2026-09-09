package com.shangan.todo.application;

import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 首页的日 / 周 / 月视图与未完成汇总。周月只返回聚合摘要，展开某天时再取明细。 */
@Service
public class TodoViewService {

  private final TodoRepository todos;
  private final CourseRepository courses;
  private final UserTimeService userTime;

  public TodoViewService(TodoRepository todos, CourseRepository courses, UserTimeService userTime) {
    this.todos = todos;
    this.courses = courses;
    this.userTime = userTime;
  }

  /** 日视图：分组列表 + 当日四项指标。 */
  @Transactional(readOnly = true)
  public DayView day(String userId, String requestedDate) {
    User user = userTime.requireUser(userId);
    LocalDate today = userTime.today(user);
    LocalDate date = parseOr(requestedDate, today);
    List<Todo> items = todos.findByUserAndDate(userId, date);
    List<TodoView> views = decorate(items);
    return new DayView(
        date,
        date.isEqual(today),
        date.isBefore(today),
        views,
        summarize(views),
        deletionCount(user, date),
        date.equals(today)
            ? decorate(
                todos.findRepaymentTodos(
                    userId,
                    date,
                    userTime.startOfDay(user, date),
                    userTime.endOfDayExclusive(user, date)))
            : List.of(),
        RepaymentTotals.summarize(
            todos.aggregateDurations(
                userId, userTime.startOfDay(user, date), userTime.endOfDayExclusive(user, date)),
            todos.findCompletions(
                userId, userTime.startOfDay(user, date), userTime.endOfDayExclusive(user, date)),
            instant -> userTime.localDateOf(user, instant)));
  }

  /** 周视图：七天摘要 + 本周合计。 */
  @Transactional(readOnly = true)
  public RangeView week(String userId, String weekStart) {
    User user = userTime.requireUser(userId);
    LocalDate today = userTime.today(user);
    LocalDate start = parseOr(weekStart, today.minusDays(today.getDayOfWeek().getValue() - 1L));
    LocalDate end = start.plusDays(6);
    return range(userId, today, start, end);
  }

  /** 月视图：整月每日摘要 + 本月合计。 */
  @Transactional(readOnly = true)
  public RangeView month(String userId, String month) {
    User user = userTime.requireUser(userId);
    LocalDate today = userTime.today(user);
    YearMonth target = month == null || month.isBlank() ? YearMonth.from(today) : parseMonth(month);
    return range(userId, today, target.atDay(1), target.atEndOfMonth());
  }

  /** 未完成汇总：跨日期聚合历史未完成项，按逾期天数分组。 */
  @Transactional(readOnly = true)
  public PendingSummary pendingSummary(String userId) {
    User user = userTime.requireUser(userId);
    LocalDate today = userTime.today(user);
    List<Todo> pending = todos.findPendingBefore(userId, today);
    List<PendingItem> items = new ArrayList<>();
    for (TodoView view : decorate(pending)) {
      long overdueDays = ChronoUnit.DAYS.between(view.localDate(), today);
      items.add(new PendingItem(view, overdueDays, bucketOf(overdueDays)));
    }
    Map<TodoType, Integer> byType = new LinkedHashMap<>();
    for (PendingItem item : items) {
      byType.merge(item.todo().todoType(), 1, Integer::sum);
    }
    return new PendingSummary(items.size(), byType, List.copyOf(items));
  }

  private RangeView range(String userId, LocalDate today, LocalDate start, LocalDate end) {
    List<Todo> items = todos.findByUserBetween(userId, start, end);
    Map<LocalDate, List<Todo>> byDate = new LinkedHashMap<>();
    for (Todo todo : items) {
      byDate.computeIfAbsent(todo.localDate(), key -> new ArrayList<>()).add(todo);
    }
    List<DaySummary> days = new ArrayList<>();
    for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
      List<Todo> dayItems = byDate.getOrDefault(cursor, List.of());
      days.add(daySummary(cursor, today, dayItems));
    }
    return new RangeView(start, end, List.copyOf(days), summarizeTodos(items));
  }

  private DaySummary daySummary(LocalDate date, LocalDate today, List<Todo> items) {
    int total = items.size();
    int done = (int) items.stream().filter(Todo::done).count();
    long watchedMs = items.stream().mapToLong(Todo::watchedMs).sum();
    long focusedMs = items.stream().mapToLong(Todo::focusedMs).sum();
    Map<TodoType, Integer> byType = new LinkedHashMap<>();
    for (Todo todo : items) {
      byType.merge(todo.todoType(), 1, Integer::sum);
    }
    DayOutcome outcome;
    if (total == 0) {
      outcome = DayOutcome.NO_TODOS;
    } else if (done == total) {
      outcome = DayOutcome.ALL_DONE;
    } else if (done == 0) {
      outcome = DayOutcome.NONE_DONE;
    } else {
      outcome = DayOutcome.PARTIAL;
    }
    return new DaySummary(
        date,
        date.isEqual(today),
        total,
        done,
        total - done,
        watchedMs,
        focusedMs,
        byType,
        outcome);
  }

  /** 把 Todo 行装饰为客户端视图：补齐资源信息、进度千分比、附件数与备注标签。 */
  private List<TodoView> decorate(List<Todo> items) {
    if (items.isEmpty()) {
      return List.of();
    }
    List<String> ids = items.stream().map(Todo::id).toList();
    Map<String, Integer> attachmentCounts = todos.attachmentCounts(ids);
    Map<String, Set<NoteTag>> noteTags = todos.noteTagsOf(ids);
    Set<String> reviewIds = todos.reviewTodoIds(ids);
    List<TodoView> views = new ArrayList<>(items.size());
    for (Todo todo : items) {
      Optional<LearningResource> resource =
          todo.resourceId() == null
              ? Optional.empty()
              : courses.findResourceById(todo.resourceId());
      int permille =
          resource
              .map(value -> value.progressPermille(todo.progressPositionMs(), todo.progressPage()))
              .orElse(0);
      views.add(
          new TodoView(
              todo.id(),
              todo.localDate(),
              todo.todoType(),
              todo.title(),
              todo.note(),
              todo.sortOrder(),
              todo.status(),
              todo.resourceId(),
              resource.map(LearningResource::resourceType).orElse(null),
              resource.map(LearningResource::title).orElse(null),
              resource.map(LearningResource::durationMs).orElse(null),
              resource.map(LearningResource::pageCount).orElse(null),
              resource.map(LearningResource::available).orElse(true),
              todo.targetProgressPermille(),
              permille,
              todo.progressPositionMs(),
              todo.progressPage(),
              todo.watchedMs(),
              todo.plannedSeconds(),
              todo.focusState(),
              todo.focusStartedAt(),
              todo.focusAttemptBaseMs(),
              todo.focusedMs(),
              todo.requireEvidence(),
              todo.completedAt(),
              todo.backfilled(),
              todo.supervisorUserIdSnapshot(),
              attachmentCounts.getOrDefault(todo.id(), 0),
              List.copyOf(noteTags.getOrDefault(todo.id(), Set.of())),
              reviewIds.contains(todo.id())));
    }
    return List.copyOf(views);
  }

  private DaySummaryTotals summarize(List<TodoView> views) {
    int total = views.size();
    int done = (int) views.stream().filter(view -> view.status() == TodoStatus.DONE).count();
    long watched = views.stream().mapToLong(TodoView::watchedMs).sum();
    long focused = views.stream().mapToLong(TodoView::focusedMs).sum();
    int attachments = views.stream().mapToInt(TodoView::attachmentCount).sum();
    return new DaySummaryTotals(total, done, watched, focused, attachments);
  }

  private DaySummaryTotals summarizeTodos(List<Todo> items) {
    int total = items.size();
    int done = (int) items.stream().filter(Todo::done).count();
    long watched = items.stream().mapToLong(Todo::watchedMs).sum();
    long focused = items.stream().mapToLong(Todo::focusedMs).sum();
    return new DaySummaryTotals(total, done, watched, focused, 0);
  }

  private int deletionCount(User user, LocalDate date) {
    return todos.countDeletionsOn(user.id(), date);
  }

  private OverdueBucket bucketOf(long overdueDays) {
    if (overdueDays >= 3) {
      return OverdueBucket.THREE_DAYS_OR_MORE;
    }
    return OverdueBucket.ONE_TO_TWO_DAYS;
  }

  private LocalDate parseOr(String value, LocalDate fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException exception) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_DATE_INVALID", "日期格式必须为 YYYY-MM-DD");
    }
  }

  private YearMonth parseMonth(String value) {
    try {
      return YearMonth.parse(value);
    } catch (RuntimeException exception) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_MONTH_INVALID", "月份格式必须为 YYYY-MM");
    }
  }

  /** 当日结果分档，用于周月视图着色与热力图。 */
  public enum DayOutcome {
    NO_TODOS,
    NONE_DONE,
    PARTIAL,
    ALL_DONE
  }

  /** 逾期分组。 */
  public enum OverdueBucket {
    ONE_TO_TWO_DAYS,
    THREE_DAYS_OR_MORE
  }

  /** 客户端渲染一行 Todo 所需的全部字段。 */
  public record TodoView(
      String id,
      LocalDate localDate,
      TodoType todoType,
      String title,
      String note,
      int sortOrder,
      TodoStatus status,
      String resourceId,
      ResourceType resourceType,
      String resourceTitle,
      Long resourceDurationMs,
      Integer resourcePageCount,
      boolean resourceAvailable,
      Integer targetProgressPermille,
      int progressPermille,
      long progressPositionMs,
      int progressPage,
      long watchedMs,
      Integer plannedSeconds,
      FocusState focusState,
      java.time.Instant focusStartedAt,
      long focusAttemptBaseMs,
      long focusedMs,
      boolean requireEvidence,
      java.time.Instant completedAt,
      boolean backfilled,
      String supervisorUserIdSnapshot,
      int attachmentCount,
      List<NoteTag> noteTags,
      boolean review) {}

  /** 当日指标。 */
  public record DaySummaryTotals(
      int total, int done, long watchedMs, long focusedMs, int attachmentCount) {}

  /** 日视图。 */
  public record DayView(
      LocalDate date,
      boolean today,
      boolean history,
      List<TodoView> todos,
      DaySummaryTotals totals,
      int deletionCount,
      List<TodoView> repaymentTodos,
      RepaymentTotals repayment) {
    /** 原日视图构造入口保留，未提供还债数据时为空。 */
    public DayView(
        LocalDate date,
        boolean today,
        boolean history,
        List<TodoView> todos,
        DaySummaryTotals totals,
        int deletionCount) {
      this(date, today, history, todos, totals, deletionCount, List.of(), RepaymentTotals.EMPTY);
    }
  }

  /** 周 / 月视图中的单日摘要。 */
  public record DaySummary(
      LocalDate date,
      boolean today,
      int total,
      int done,
      int pending,
      long watchedMs,
      long focusedMs,
      Map<TodoType, Integer> countByType,
      DayOutcome outcome) {}

  /** 周 / 月聚合视图。 */
  public record RangeView(
      LocalDate start, LocalDate end, List<DaySummary> days, DaySummaryTotals totals) {}

  /** 未完成汇总中的一项。 */
  public record PendingItem(TodoView todo, long overdueDays, OverdueBucket bucket) {}

  /** 未完成汇总。 */
  public record PendingSummary(
      int total, Map<TodoType, Integer> countByType, List<PendingItem> items) {}
}
