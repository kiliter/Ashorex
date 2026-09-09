package com.shangan.todo.application;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.supervision.application.SupervisionService;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoPolicy;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Todo 的创建、编辑、排序与顺延。日期一律由服务端按用户时区生成。 */
@Service
public class TodoService {

  private static final int MAX_TODOS_PER_DAY = 40;

  private final TodoRepository todos;
  private final CatalogQueryService catalog;
  private final UserTimeService userTime;
  private final SupervisionService supervisions;
  private final EffectiveActionRecorder effectiveAction;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public TodoService(
      TodoRepository todos,
      CatalogQueryService catalog,
      UserTimeService userTime,
      SupervisionService supervisions,
      EffectiveActionRecorder effectiveAction,
      IdGenerator idGenerator,
      Clock clock) {
    this.todos = todos;
    this.catalog = catalog;
    this.userTime = userTime;
    this.supervisions = supervisions;
    this.effectiveAction = effectiveAction;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  /** 兼容原创建入口，同步跳过今日重复和未经确认的历史课时。 */
  @Transactional
  public List<Todo> create(String userId, List<CreateTodoCommand> commands) {
    return applyAdditions(userId, commands, List.of()).accepted();
  }

  /** 只读预览，历史未完成项由用户逐课时确认，不在此阶段写入。 */
  @Transactional(readOnly = true)
  public CourseAdditionPreview previewCourseAdditions(
      String userId, List<CreateTodoCommand> commands) {
    validateCommands(commands, true);
    User user = userTime.requireUser(userId);
    List<CourseAdditionItem> items = new ArrayList<>();
    var seen = new java.util.HashSet<String>();
    for (CreateTodoCommand original : commands) {
      CreateTodoCommand command = highestTarget(user, original, commands);
      LocalDate date = resolveDate(user, command.localDate());
      String resourceId = requireResourceId(command);
      if (!seen.add(date + ":" + resourceId)) {
        items.add(new CourseAdditionItem(resourceId, "本批重复课时", "DUPLICATE", List.of()));
        continue;
      }
      items.add(
          classifyCourse(
                  command,
                  todos.findByUserAndDate(userId, date),
                  todos.findPendingBefore(userId, date))
              .withReviewAvailable(todos.hasCourseHistory(userId, resourceId)));
    }
    return new CourseAdditionPreview(List.copyOf(items));
  }

  /** 确认后再次读取状态；短事务内复用或顺延并取较高目标，不复制或重置学习记录。 */
  @Transactional
  public CourseAdditionResult addCourses(
      String userId, List<CreateTodoCommand> commands, List<String> reuseTodoIds) {
    validateCommands(commands, true);
    return applyAdditions(userId, commands, reuseTodoIds == null ? List.of() : reuseTodoIds);
  }

  /** 新版添加显式选择复习，整个批次用稳定请求标识重放，旧请求保持原去重行为。 */
  @Transactional
  public CourseAdditionResult addCourses(
      String userId,
      List<CreateTodoCommand> commands,
      List<String> reuseTodoIds,
      List<String> reviewResourceIds,
      String requestId) {
    List<String> reviews = reviewResourceIds == null ? List.of() : reviewResourceIds;
    List<String> reuse = reuseTodoIds == null ? List.of() : reuseTodoIds;
    if (requestId == null && reviews.isEmpty()) return addCourses(userId, commands, reuse);
    validateCommands(commands, true);
    if (requestId == null
        || !requestId.matches("[A-Za-z0-9-]{8,100}")
        || reviews.stream()
            .anyMatch(
                id -> id == null || commands.stream().noneMatch(c -> id.equals(c.resourceId())))) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_BATCH_INVALID", "复习添加请求无效，请重新选择课时");
    }
    try {
      // 回执只存响应和内容摘要，不保存备注等请求明文；先写占位取得 SQLite 写锁。
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      String fingerprint =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(json.writeValueAsBytes(List.of(commands, reuse, reviews))));
      todos.reserveCourseAddition(userId, requestId, fingerprint);
      var receipt = todos.courseAdditionReceipt(userId, requestId);
      if (receipt.isPresent()) {
        if (!fingerprint.equals(receipt.get().fingerprint())) {
          throw new BusinessException(HttpStatus.CONFLICT, "TODO_ADDITION_STALE", "添加内容已变化，请重新确认");
        }
        if (receipt.get().resultJson() != null)
          return json.readValue(receipt.get().resultJson(), CourseAdditionResult.class);
      }
      var result = applyAdditions(userId, commands, reuse, Set.copyOf(reviews), true);
      todos.completeCourseAddition(userId, requestId, json.writeValueAsString(result));
      return result;
    } catch (com.fasterxml.jackson.core.JsonProcessingException
        | java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("课程添加回执处理失败", error);
    }
  }

  /** 原入口允许三类 Todo，新入口仅接受课程；禁止空请求或超过合理批量上限。 */
  private void validateCommands(List<CreateTodoCommand> commands, boolean coursesOnly) {
    if (commands == null || commands.isEmpty()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_EMPTY_REQUEST", "没有要添加的待办");
    }
    if (commands.size() > 500
        || commands.stream()
            .anyMatch(
                command ->
                    command == null
                        || command.todoType() == null
                        || coursesOnly && command.todoType() != TodoType.COURSE)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_BATCH_INVALID", "待办批量请求无效");
    }
  }

  /** 分类只比较稳定资源 ID，不按标题或当前播放位置判断重复。 */
  private CourseAdditionItem classifyCourse(
      CreateTodoCommand command, List<Todo> current, List<Todo> pending) {
    String resourceId = requireResourceId(command);
    int target = normalizeTarget(command.targetProgressPermille());
    List<Todo> sameDay =
        current.stream().filter(todo -> resourceId.equals(todo.resourceId())).toList();
    if (!sameDay.isEmpty()) {
      boolean sameTarget =
          sameDay.stream()
              .anyMatch(todo -> java.util.Objects.equals(todo.targetProgressPermille(), target));
      return new CourseAdditionItem(
          resourceId,
          sameDay.getFirst().title(),
          sameTarget ? "EXISTING" : "TARGET_CONFLICT",
          sameTarget
              ? List.of()
              : sameDay.stream().filter(todo -> !todo.done()).map(this::historyOption).toList());
    }
    List<HistoryCourseTodo> history =
        pending.stream()
            .filter(todo -> !todo.done() && resourceId.equals(todo.resourceId()))
            .map(
                todo ->
                    new HistoryCourseTodo(
                        todo.id(),
                        todo.localDate().toString(),
                        todo.title(),
                        todo.targetProgressPermille(),
                        todo.progressPositionMs(),
                        todo.watchedMs()))
            .toList();
    if (!history.isEmpty())
      return new CourseAdditionItem(resourceId, history.getFirst().title(), "HISTORY", history);
    return new CourseAdditionItem(
        resourceId, catalog.requireVisibleResource(resourceId).title(), "NEW", List.of());
  }

  /** 今日复用和历史顺延统一展示原目标与已看位置。 */
  private HistoryCourseTodo historyOption(Todo todo) {
    return new HistoryCourseTodo(
        todo.id(),
        todo.localDate().toString(),
        todo.title(),
        todo.targetProgressPermille(),
        todo.progressPositionMs(),
        todo.watchedMs());
  }

  /** 提交采用事务内最新列表，批内重复也会命中已处理项；不会重复累计单日数量。 */
  private CourseAdditionResult applyAdditions(
      String userId, List<CreateTodoCommand> commands, List<String> reuseIds) {
    return applyAdditions(userId, commands, reuseIds, Set.of(), false);
  }

  /** 主动复习只新建所选资源，批内重复仍按日期和课时折叠。 */
  private CourseAdditionResult applyAdditions(
      String userId,
      List<CreateTodoCommand> commands,
      List<String> reuseIds,
      Set<String> reviewIds,
      boolean modern) {
    validateCommands(commands, false);
    User user = userTime.requireUser(userId);
    Instant now = clock.instant();
    String supervisor = supervisions.primarySupervisorOf(userId).orElse(null);
    var dates = new java.util.HashMap<LocalDate, List<Todo>>();
    var confirmed = new java.util.HashMap<String, Todo>();
    // 不接受跨用户、已完成、日期已变或与本批课时无关的确认 ID。
    for (String id : new java.util.LinkedHashSet<>(reuseIds)) {
      Todo old = requireOwned(userId, id);
      if (reviewIds.contains(old.resourceId())) {
        throw new BusinessException(
            HttpStatus.BAD_REQUEST, "TODO_BATCH_INVALID", "同一课时不能同时复用和新增复习");
      }
      var targets =
          commands.stream()
              .filter(
                  command ->
                      command.todoType() == TodoType.COURSE
                          && java.util.Objects.equals(command.resourceId(), old.resourceId()))
              .map(command -> resolveDate(user, command.localDate()))
              .distinct()
              .toList();
      if (targets.size() != 1
          || old.localDate().isAfter(targets.getFirst())
          || old.done() && !old.localDate().equals(targets.getFirst())
          || confirmed.putIfAbsent(old.resourceId(), old) != null) {
        throw new BusinessException(HttpStatus.CONFLICT, "TODO_ADDITION_STALE", "历史待办已变化，请重新预览后确认");
      }
    }
    List<Todo> accepted = new ArrayList<>();
    List<CourseAdditionOutcome> outcomes = new ArrayList<>();
    int created = 0;
    int deferred = 0;
    int reused = 0;
    var processed = new java.util.HashSet<String>();
    for (CreateTodoCommand original : commands) {
      CreateTodoCommand command = original;
      if (original.todoType() == TodoType.COURSE) {
        // 同批同课时统一采用最高目标，后续重复项只计为跳过。
        LocalDate requestedDate = resolveDate(user, original.localDate());
        command = highestTarget(user, original, commands);
        if (!processed.add(requestedDate + ":" + requireResourceId(command))) {
          outcomes.add(
              new CourseAdditionOutcome(command.resourceId(), "重复课时", "SKIPPED_DUPLICATE", null));
          continue;
        }
      }
      LocalDate date = resolveDate(user, command.localDate());
      List<Todo> current =
          dates.computeIfAbsent(
              date, value -> new ArrayList<>(todos.findByUserAndDate(userId, value)));
      if (command.todoType() == TodoType.COURSE) {
        CourseAdditionItem item =
            classifyCourse(command, current, todos.findPendingBefore(userId, date));
        // 跨日已完成项在旧预览中仍是 NEW；新版选择跳过时不能偷偷新建。
        if (modern
            && "NEW".equals(item.status())
            && !reviewIds.contains(item.resourceId())
            && todos.hasCourseHistory(userId, item.resourceId())) {
          outcomes.add(
              new CourseAdditionOutcome(item.resourceId(), item.title(), "SKIPPED_EXISTING", null));
          continue;
        }
        if (!"NEW".equals(item.status()) && !reviewIds.contains(item.resourceId())) {
          Todo old = confirmed.get(item.resourceId());
          if (("HISTORY".equals(item.status()) || "TARGET_CONFLICT".equals(item.status()))
              && old != null) {
            if (old.done()
                || item.history().stream().noneMatch(value -> value.id().equals(old.id()))) {
              throw new BusinessException(
                  HttpStatus.CONFLICT, "TODO_ADDITION_STALE", "原待办已变化，请重新预览后确认");
            }
            // 下架或归档课时只能完成/删除；复用入口不能绕过课程可用性裁决。
            catalog.requireVisibleResource(item.resourceId());
            // 历史项复用时保留原日期；今日计划仍可调整到未来。
            boolean moving =
                old.localDate().isBefore(date) && !old.localDate().isBefore(userTime.today(user));
            if (moving) {
              requireCapacity(current.size());
              todos.updateLocalDate(old.id(), date, todos.nextSortOrder(userId, date), now);
              current.add(old);
            }
            int target =
                Math.max(
                    old.targetProgressPermille(),
                    normalizeTarget(command.targetProgressPermille()));
            if (target != old.targetProgressPermille()) {
              // 仅提高目标，原进度、时长、状态、凭证与备注全部保留。
              todos.updateEditableFields(
                  old.id(),
                  old.title(),
                  old.note(),
                  target,
                  old.plannedSeconds(),
                  old.requireEvidence(),
                  now);
            }
            accepted.add(old);
            outcomes.add(
                new CourseAdditionOutcome(
                    item.resourceId(), item.title(), moving ? "DEFERRED" : "REUSED", old.id()));
            if (moving) deferred++;
            else reused++;
          } else {
            outcomes.add(
                new CourseAdditionOutcome(
                    item.resourceId(), item.title(), "SKIPPED_" + item.status(), null));
          }
          continue;
        }
      }
      requireCapacity(current.size());
      boolean review =
          modern
              && command.todoType() == TodoType.COURSE
              && todos.hasCourseHistory(userId, command.resourceId());
      Todo added = buildAndInsert(user, command, date, supervisor, now);
      if (review) todos.markReview(added.id());
      current.add(added);
      accepted.add(added);
      outcomes.add(
          new CourseAdditionOutcome(added.resourceId(), added.title(), "CREATED", added.id()));
      created++;
    }
    if (!accepted.isEmpty()) effectiveAction.record(userId);
    return new CourseAdditionResult(
        created,
        deferred,
        reused,
        commands.size() - created - deferred - reused,
        List.copyOf(outcomes),
        List.copyOf(accepted));
  }

  /** 预览与提交使用同一批内最高目标，避免同一课时重复输入导致数量或目标漂移。 */
  private CreateTodoCommand highestTarget(
      User user, CreateTodoCommand original, List<CreateTodoCommand> commands) {
    LocalDate requestedDate = resolveDate(user, original.localDate());
    int highest =
        commands.stream()
            .filter(
                value ->
                    value.todoType() == TodoType.COURSE
                        && java.util.Objects.equals(value.resourceId(), original.resourceId())
                        && resolveDate(user, value.localDate()).equals(requestedDate))
            .mapToInt(value -> normalizeTarget(value.targetProgressPermille()))
            .max()
            .orElse(1000);
    return new CreateTodoCommand(
        original.todoType(),
        original.localDate(),
        original.title(),
        original.note(),
        original.resourceId(),
        highest,
        original.plannedSeconds(),
        original.requireEvidence(),
        original.noteTags());
  }

  /** 单日上限只按最终新增或顺延计算，跳过项不占配额。 */
  private void requireCapacity(int count) {
    if (count >= MAX_TODOS_PER_DAY)
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_DAILY_LIMIT", "单日待办数量已达上限 " + MAX_TODOS_PER_DAY);
  }

  /** 预览仅提供确认所需的历史状态，不复制附件或流水。 */
  public record HistoryCourseTodo(
      String id,
      String localDate,
      String title,
      Integer targetProgressPermille,
      long progressPositionMs,
      long watchedMs) {}

  public record CourseAdditionItem(
      String resourceId,
      String title,
      String status,
      List<HistoryCourseTodo> history,
      boolean reviewAvailable) {
    /** 旧分类不变，复习能力作为旧客户端可忽略的增量字段。 */
    public CourseAdditionItem(
        String resourceId, String title, String status, List<HistoryCourseTodo> history) {
      this(resourceId, title, status, history, false);
    }

    CourseAdditionItem withReviewAvailable(boolean available) {
      return new CourseAdditionItem(resourceId, title, status, history, available);
    }
  }

  public record CourseAdditionPreview(List<CourseAdditionItem> items) {}

  public record CourseAdditionOutcome(
      String resourceId, String title, String status, String todoId) {}

  public record CourseAdditionResult(
      int created,
      int deferred,
      int reused,
      int skipped,
      List<CourseAdditionOutcome> items,
      @com.fasterxml.jackson.annotation.JsonIgnore List<Todo> accepted) {}

  private Todo buildAndInsert(
      User user, CreateTodoCommand command, LocalDate date, String supervisor, Instant now) {
    String title;
    String resourceId = null;
    Integer target = null;
    Integer plannedSeconds = null;
    switch (command.todoType()) {
      case COURSE -> {
        LearningResource resource = catalog.requireVisibleResource(requireResourceId(command));
        if (!resource.measurable()) {
          throw new BusinessException(
              HttpStatus.CONFLICT,
              "RESOURCE_NOT_MEASURABLE",
              resource.resourceType() == ResourceType.DOCUMENT
                  ? "该材料尚未回报页数，暂时无法加入待办"
                  : "该课时缺少时长信息，无法计算目标进度");
        }
        resourceId = resource.id();
        target = normalizeTarget(command.targetProgressPermille());
        title =
            command.title() == null || command.title().isBlank()
                ? resource.title()
                : command.title().trim();
      }
      case FOCUS -> {
        plannedSeconds = requirePlannedSeconds(command.plannedSeconds());
        title = requireTitle(command.title());
      }
      case TASK -> title = requireTitle(command.title());
      default ->
          throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_TYPE_INVALID", "未知的待办类型");
    }
    Todo todo =
        new Todo(
            idGenerator.nextId(),
            user.id(),
            date,
            command.todoType(),
            title,
            command.note() == null ? "" : command.note().trim(),
            todos.nextSortOrder(user.id(), date),
            TodoStatus.TODO,
            resourceId,
            target,
            0L,
            0,
            0L,
            plannedSeconds,
            FocusState.IDLE,
            null,
            0L,
            command.requireEvidence(),
            null,
            false,
            null,
            supervisor,
            0L);
    todos.insert(todo, now);
    if (command.noteTags() != null && !command.noteTags().isEmpty()) {
      todos.replaceNoteTags(todo.id(), Set.copyOf(command.noteTags()));
    }
    return todo;
  }

  /** 完成时固化当前主督学人；创建后的改绑不应沿用旧快照，重放不改写已完成记录。 */
  @Transactional
  public void snapshotCompletion(Todo before) {
    if (!before.done()) {
      todos.updateCompletionSupervisor(
          before.id(), supervisions.primarySupervisorOf(before.userId()).orElse(null));
    }
  }

  /** 修改标题、备注、目标进度、计划时长与凭证要求。 */
  @Transactional
  public Todo patch(String userId, String todoId, PatchTodoCommand command) {
    Todo todo = requireOwned(userId, todoId);
    Integer target =
        command.targetProgressPermille() == null
            ? null
            : normalizeTarget(command.targetProgressPermille());
    if (target != null && todo.todoType() != TodoType.COURSE) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_TARGET_NOT_APPLICABLE", "只有课程待办可以设置目标进度");
    }
    Integer planned =
        command.plannedSeconds() == null ? null : requirePlannedSeconds(command.plannedSeconds());
    if (planned != null && todo.todoType() != TodoType.FOCUS) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_PLANNED_NOT_APPLICABLE", "只有专注待办可以设置倒计时");
    }
    todos.updateEditableFields(
        todo.id(),
        command.title() == null || command.title().isBlank()
            ? todo.title()
            : command.title().trim(),
        command.note() == null ? todo.note() : command.note().trim(),
        target,
        planned,
        command.requireEvidence() == null ? todo.requireEvidence() : command.requireEvidence(),
        clock.instant());
    if (command.noteTags() != null) {
      todos.replaceNoteTags(todo.id(), Set.copyOf(command.noteTags()));
    }
    effectiveAction.record(userId);
    return requireOwned(userId, todoId);
  }

  /** 整日重排；只写 sort_order。 */
  @Transactional
  public void reorder(String userId, String localDate, List<String> orderedIds) {
    User user = userTime.requireUser(userId);
    LocalDate date = resolveDate(user, localDate);
    List<Todo> existing = todos.findByUserAndDate(userId, date);
    Set<String> owned =
        existing.stream().map(Todo::id).collect(java.util.stream.Collectors.toSet());
    Instant now = clock.instant();
    int order = 0;
    for (String todoId : orderedIds) {
      if (!owned.contains(todoId)) {
        throw new BusinessException(
            HttpStatus.BAD_REQUEST, "TODO_REORDER_MISMATCH", "排序列表包含不属于该日期的待办");
      }
      todos.updateSortOrder(todoId, order++, now);
    }
  }

  /** 顺延到指定日期；保留全部进度与附件，不计入删除统计。 */
  @Transactional
  public Todo defer(String userId, String todoId, String targetDate) {
    Todo todo = requireOwned(userId, todoId);
    // 历史任务统一原地执行，禁止旧客户端继续将历史计划挪到今天。
    if (todo.localDate().isBefore(userTime.today(userTime.requireUser(userId)))) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_HISTORY_DEFER_REMOVED", "历史待办请在今日还债中继续执行");
    }
    // 顺延是新的学习安排，不允许把已下架课时再次安排到其他日期。
    if (todo.todoType() == TodoType.COURSE) catalog.requireVisibleResource(todo.resourceId());
    User user = userTime.requireUser(userId);
    LocalDate date = resolveDate(user, targetDate);
    todos.updateLocalDate(todo.id(), date, todos.nextSortOrder(userId, date), clock.instant());
    effectiveAction.record(userId);
    return requireOwned(userId, todoId);
  }

  /** 批量顺延，常用于「未完成汇总」页一次性挪到今天。 */
  @Transactional
  public int deferAll(String userId, List<String> todoIds, String targetDate) {
    int moved = 0;
    for (String todoId : todoIds) {
      defer(userId, todoId, targetDate);
      moved++;
    }
    return moved;
  }

  @Transactional(readOnly = true)
  public Todo requireOwned(String userId, String todoId) {
    Todo todo =
        todos
            .findById(todoId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "待办不存在"));
    if (!todo.userId().equals(userId)) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "待办不存在");
    }
    return todo;
  }

  private LocalDate resolveDate(User user, String requested) {
    if (requested == null || requested.isBlank()) {
      return userTime.today(user);
    }
    try {
      return LocalDate.parse(requested);
    } catch (RuntimeException exception) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_DATE_INVALID", "日期格式必须为 YYYY-MM-DD");
    }
  }

  private String requireResourceId(CreateTodoCommand command) {
    if (command.resourceId() == null || command.resourceId().isBlank()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_RESOURCE_REQUIRED", "课程待办必须选择一个课时");
    }
    return command.resourceId();
  }

  private String requireTitle(String title) {
    String normalized = title == null ? "" : title.trim();
    if (normalized.isEmpty() || normalized.length() > 120) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_TITLE_INVALID", "标题必须为 1 到 120 个字符");
    }
    return normalized;
  }

  private int normalizeTarget(Integer permille) {
    int value = permille == null ? 1000 : permille;
    if (value < 1 || value > 1000) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_TARGET_INVALID", "目标进度必须在 1 到 1000 千分比之间");
    }
    return value;
  }

  private int requirePlannedSeconds(Integer plannedSeconds) {
    int value = plannedSeconds == null ? 0 : plannedSeconds;
    if (value < TodoPolicy.MIN_PLANNED_SECONDS || value > TodoPolicy.MAX_PLANNED_SECONDS) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "TODO_PLANNED_INVALID",
          "倒计时必须在 "
              + TodoPolicy.MIN_PLANNED_SECONDS
              + " 到 "
              + TodoPolicy.MAX_PLANNED_SECONDS
              + " 秒之间");
    }
    return value;
  }

  /** 创建命令；不同类型只需填对应字段。 */
  public record CreateTodoCommand(
      TodoType todoType,
      String localDate,
      String title,
      String note,
      String resourceId,
      Integer targetProgressPermille,
      Integer plannedSeconds,
      boolean requireEvidence,
      List<NoteTag> noteTags) {}

  /** 修改命令；null 表示不修改该字段。 */
  public record PatchTodoCommand(
      String title,
      String note,
      Integer targetProgressPermille,
      Integer plannedSeconds,
      Boolean requireEvidence,
      List<NoteTag> noteTags) {}
}
