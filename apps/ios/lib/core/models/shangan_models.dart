/// 上岸 V2 移动端共享数据模型。
///
/// 所有模型都是服务端 DTO 的直接映射，业务判定（是否达标、是否完成）一律由服务端裁决，
/// 客户端不自行推算。
library;

/// Todo 类型。
enum TodoType {
  course,
  focus,
  task;

  static TodoType parse(String value) => switch (value.toUpperCase()) {
    'FOCUS' => TodoType.focus,
    'TASK' => TodoType.task,
    _ => TodoType.course,
  };

  String get wire => switch (this) {
    TodoType.course => 'COURSE',
    TodoType.focus => 'FOCUS',
    TodoType.task => 'TASK',
  };

  String get label => switch (this) {
    TodoType.course => '课程',
    TodoType.focus => '专注',
    TodoType.task => '待办',
  };
}

/// Todo 状态。
enum TodoStatus {
  todo,
  inProgress,
  done;

  static TodoStatus parse(String value) => switch (value.toUpperCase()) {
    'IN_PROGRESS' => TodoStatus.inProgress,
    'DONE' => TodoStatus.done,
    _ => TodoStatus.todo,
  };

  bool get isDone => this == TodoStatus.done;
}

/// 专注状态机。
enum FocusState {
  idle,
  running,
  paused,
  stopped,
  finished,
  abandoned;

  static FocusState parse(String value) => switch (value.toUpperCase()) {
    'RUNNING' => FocusState.running,
    'PAUSED' => FocusState.paused,
    'STOPPED' => FocusState.stopped,
    'FINISHED' => FocusState.finished,
    'ABANDONED' => FocusState.abandoned,
    _ => FocusState.idle,
  };

  bool get isActive => this == FocusState.running || this == FocusState.paused;
}

/// 备注一键标签。
enum NoteTag {
  mastered('MASTERED', '已掌握'),
  needReview('NEED_REVIEW', '需重看'),
  hasQuestion('HAS_QUESTION', '有疑问'),
  noted('NOTED', '已做笔记'),
  behind('BEHIND', '进度落后');

  const NoteTag(this.wire, this.label);

  final String wire;
  final String label;

  static NoteTag? tryParse(String value) {
    for (final tag in NoteTag.values) {
      if (tag.wire == value.toUpperCase()) return tag;
    }
    return null;
  }
}

/// 删除原因标签；删除任意 Todo 都必须选择其一。
enum DeletionReasonTag {
  tooManyPlanned('TOO_MANY_PLANNED', '计划排太多'),
  tempBusy('TEMP_BUSY', '今天临时有事'),
  addedByMistake('ADDED_BY_MISTAKE', '加错了'),
  switchedToOther('SWITCHED_TO_OTHER', '改到别的课时'),
  gaveUp('GAVE_UP', '不想学了');

  const DeletionReasonTag(this.wire, this.label);

  final String wire;
  final String label;
}

/// 催办回应原因标签。
enum NagReasonTag {
  tempBusy('TEMP_BUSY', '临时加班'),
  unwell('UNWELL', '身体不适'),
  emergency('EMERGENCY', '突发事情'),
  lazy('LAZY', '纯粹偷懒'),
  planError('PLAN_ERROR', '计划有误');

  const NagReasonTag(this.wire, this.label);

  final String wire;
  final String label;
}

/// 资源类型；材料在 V2 默认关闭。
enum ResourceType {
  video,
  document;

  static ResourceType parse(String? value) =>
      value != null && value.toUpperCase() == 'DOCUMENT'
      ? ResourceType.document
      : ResourceType.video;
}

/// 目标紧急度分档。
enum GoalUrgency {
  normal,
  soon,
  urgent,
  expired;

  static GoalUrgency parse(String value) => switch (value.toUpperCase()) {
    'SOON' => GoalUrgency.soon,
    'URGENT' => GoalUrgency.urgent,
    'EXPIRED' => GoalUrgency.expired,
    _ => GoalUrgency.normal,
  };
}

/// 当日结果分档，用于周月视图与热力图着色。
enum DayOutcome {
  noTodos,
  noneDone,
  partial,
  allDone;

  static DayOutcome parse(String value) => switch (value.toUpperCase()) {
    'NONE_DONE' => DayOutcome.noneDone,
    'PARTIAL' => DayOutcome.partial,
    'ALL_DONE' => DayOutcome.allDone,
    _ => DayOutcome.noTodos,
  };
}

/// 在线状态。
enum PresenceState {
  online,
  idle,
  offline;

  static PresenceState parse(String value) => switch (value.toUpperCase()) {
    'IDLE' => PresenceState.idle,
    'OFFLINE' => PresenceState.offline,
    _ => PresenceState.online,
  };

  String get label => switch (this) {
    PresenceState.online => '在线',
    PresenceState.idle => '空闲',
    PresenceState.offline => '离线',
  };
}

/// 考试目标看板条目。
final class ExamGoal {
  const ExamGoal({
    required this.id,
    required this.name,
    required this.examDate,
    required this.note,
    required this.primary,
    required this.daysRemaining,
    required this.urgency,
  });

  final String id;
  final String name;
  final DateTime examDate;
  final String note;
  final bool primary;
  final int daysRemaining;
  final GoalUrgency urgency;

  factory ExamGoal.fromJson(Map<String, dynamic> json) {
    return ExamGoal(
      id: json['id'] as String,
      name: json['name'] as String,
      examDate: DateTime.parse(json['examDate'] as String),
      note: json['note'] as String? ?? '',
      primary: json['primary'] as bool? ?? false,
      daysRemaining: (json['daysRemaining'] as num).toInt(),
      urgency: GoalUrgency.parse(json['urgency'] as String? ?? 'NORMAL'),
    );
  }
}

/// 一行 Todo 的完整渲染数据。
final class TodoItem {
  const TodoItem({
    required this.id,
    required this.localDate,
    required this.todoType,
    required this.title,
    required this.note,
    required this.sortOrder,
    required this.status,
    required this.progressPermille,
    required this.progressPositionMs,
    required this.watchedMs,
    required this.focusState,
    required this.focusedMs,
    required this.requireEvidence,
    required this.backfilled,
    required this.attachmentCount,
    required this.noteTags,
    this.resourceId,
    this.resourceType,
    this.resourceTitle,
    this.resourceDurationMs,
    this.resourcePageCount,
    this.resourceAvailable = true,
    this.targetProgressPermille,
    this.plannedSeconds,
    this.focusStartedAt,
    this.focusAttemptBaseMs = 0,
    this.completedAt,
  });

  final String id;
  final DateTime localDate;
  final TodoType todoType;
  final String title;
  final String note;
  final int sortOrder;
  final TodoStatus status;
  final String? resourceId;
  final ResourceType? resourceType;
  final String? resourceTitle;
  final int? resourceDurationMs;
  final int? resourcePageCount;
  final bool resourceAvailable;
  final int? targetProgressPermille;
  final int progressPermille;
  final int progressPositionMs;
  final int watchedMs;
  final int? plannedSeconds;
  final FocusState focusState;
  final DateTime? focusStartedAt;
  final int focusAttemptBaseMs;
  int get focusAttemptMs =>
      (focusedMs - focusAttemptBaseMs).clamp(0, focusedMs);
  final int focusedMs;
  final bool requireEvidence;
  final DateTime? completedAt;
  final bool backfilled;
  final int attachmentCount;
  final List<NoteTag> noteTags;

  bool get isDone => status.isDone;

  /// 距目标进度还差多少千分比；已达标返回 0。
  int get remainingPermille {
    final target = targetProgressPermille;
    if (target == null) return 0;
    final remaining = target - progressPermille;
    return remaining < 0 ? 0 : remaining;
  }

  factory TodoItem.fromJson(Map<String, dynamic> json) {
    final rawTags = json['noteTags'];
    return TodoItem(
      id: json['id'] as String,
      localDate: DateTime.parse(json['localDate'] as String),
      todoType: TodoType.parse(json['todoType'] as String),
      title: json['title'] as String,
      note: json['note'] as String? ?? '',
      sortOrder: (json['sortOrder'] as num?)?.toInt() ?? 0,
      status: TodoStatus.parse(json['status'] as String),
      resourceId: json['resourceId'] as String?,
      resourceType: json['resourceType'] == null
          ? null
          : ResourceType.parse(json['resourceType'] as String),
      resourceTitle: json['resourceTitle'] as String?,
      resourceDurationMs: (json['resourceDurationMs'] as num?)?.toInt(),
      resourcePageCount: (json['resourcePageCount'] as num?)?.toInt(),
      resourceAvailable: json['resourceAvailable'] as bool? ?? true,
      targetProgressPermille: (json['targetProgressPermille'] as num?)?.toInt(),
      progressPermille: (json['progressPermille'] as num?)?.toInt() ?? 0,
      progressPositionMs: (json['progressPositionMs'] as num?)?.toInt() ?? 0,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      plannedSeconds: (json['plannedSeconds'] as num?)?.toInt(),
      focusState: FocusState.parse(json['focusState'] as String? ?? 'IDLE'),
      focusAttemptBaseMs: (json['focusAttemptBaseMs'] as num?)?.toInt() ?? 0,
      focusStartedAt: DateTime.tryParse(
        json['focusStartedAt'] as String? ?? '',
      ),
      focusedMs: (json['focusedMs'] as num?)?.toInt() ?? 0,
      requireEvidence: json['requireEvidence'] as bool? ?? false,
      completedAt: json['completedAt'] == null
          ? null
          : DateTime.parse(json['completedAt'] as String),
      backfilled: json['backfilled'] as bool? ?? false,
      attachmentCount: (json['attachmentCount'] as num?)?.toInt() ?? 0,
      noteTags: rawTags is List
          ? rawTags
                .map((tag) => NoteTag.tryParse(tag.toString()))
                .whereType<NoteTag>()
                .toList(growable: false)
          : const [],
    );
  }
}

/// 当日指标。
final class DayTotals {
  const DayTotals({
    required this.total,
    required this.done,
    required this.watchedMs,
    required this.focusedMs,
    required this.attachmentCount,
  });

  final int total;
  final int done;
  final int watchedMs;
  final int focusedMs;
  final int attachmentCount;

  int get pending => total - done;

  factory DayTotals.fromJson(Map<String, dynamic> json) {
    return DayTotals(
      total: (json['total'] as num?)?.toInt() ?? 0,
      done: (json['done'] as num?)?.toInt() ?? 0,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      focusedMs: (json['focusedMs'] as num?)?.toInt() ?? 0,
      attachmentCount: (json['attachmentCount'] as num?)?.toInt() ?? 0,
    );
  }

  static const empty = DayTotals(
    total: 0,
    done: 0,
    watchedMs: 0,
    focusedMs: 0,
    attachmentCount: 0,
  );
}

/// Todo 附件的只读投影。
///
/// 与服务端 `AttachmentView` 一一对应：服务端刻意不下发 `storagePath` 与 `sha256`，
/// 因此这里也没有对应字段。附件字节流只能通过 [downloadUrl] 指向的端点读取。
final class TodoAttachment {
  const TodoAttachment({
    required this.id,
    required this.filename,
    required this.contentType,
    required this.sizeBytes,
    required this.sortOrder,
    required this.createdAt,
    required this.downloadUrl,
  });

  final String id;
  final String filename;
  final String contentType;
  final int sizeBytes;
  final int sortOrder;
  final DateTime createdAt;

  /// 服务端给出的相对路径，仍需带 Bearer Token 请求。
  final String downloadUrl;

  bool get isPdf => contentType.toLowerCase() == 'application/pdf';

  factory TodoAttachment.fromJson(Map<String, dynamic> json) {
    return TodoAttachment(
      id: json['id'] as String,
      filename: json['filename'] as String? ?? '附件',
      contentType: json['contentType'] as String? ?? '',
      sizeBytes: (json['sizeBytes'] as num?)?.toInt() ?? 0,
      sortOrder: (json['sortOrder'] as num?)?.toInt() ?? 0,
      createdAt: DateTime.parse(json['createdAt'] as String),
      downloadUrl: json['downloadUrl'] as String? ?? '',
    );
  }
}

/// 日视图。
final class DayView {
  const DayView({
    required this.date,
    required this.today,
    required this.history,
    required this.todos,
    required this.totals,
    required this.deletionCount,
  });

  final DateTime date;
  final bool today;
  final bool history;
  final List<TodoItem> todos;
  final DayTotals totals;
  final int deletionCount;

  List<TodoItem> get inProgress => todos
      .where((todo) => todo.status == TodoStatus.inProgress)
      .toList(growable: false);

  List<TodoItem> get notStarted => todos
      .where((todo) => todo.status == TodoStatus.todo)
      .toList(growable: false);

  List<TodoItem> get completed =>
      todos.where((todo) => todo.isDone).toList(growable: false);

  factory DayView.fromJson(Map<String, dynamic> json) {
    return DayView(
      date: DateTime.parse(json['date'] as String),
      today: json['today'] as bool? ?? false,
      history: json['history'] as bool? ?? false,
      todos: (json['todos'] as List? ?? const [])
          .map((item) => TodoItem.fromJson(item as Map<String, dynamic>))
          .toList(growable: false),
      totals: DayTotals.fromJson(
        (json['totals'] as Map<String, dynamic>?) ?? const {},
      ),
      deletionCount: (json['deletionCount'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 周 / 月视图中的单日摘要。
final class DaySummary {
  const DaySummary({
    required this.date,
    required this.today,
    required this.total,
    required this.done,
    required this.pending,
    required this.watchedMs,
    required this.focusedMs,
    required this.countByType,
    required this.outcome,
  });

  final DateTime date;
  final bool today;
  final int total;
  final int done;
  final int pending;
  final int watchedMs;
  final int focusedMs;
  final Map<TodoType, int> countByType;
  final DayOutcome outcome;

  factory DaySummary.fromJson(Map<String, dynamic> json) {
    final raw = (json['countByType'] as Map?) ?? const {};
    final counts = <TodoType, int>{};
    raw.forEach((key, value) {
      counts[TodoType.parse(key.toString())] = (value as num).toInt();
    });
    return DaySummary(
      date: DateTime.parse(json['date'] as String),
      today: json['today'] as bool? ?? false,
      total: (json['total'] as num?)?.toInt() ?? 0,
      done: (json['done'] as num?)?.toInt() ?? 0,
      pending: (json['pending'] as num?)?.toInt() ?? 0,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      focusedMs: (json['focusedMs'] as num?)?.toInt() ?? 0,
      countByType: counts,
      outcome: DayOutcome.parse(json['outcome'] as String? ?? 'NO_TODOS'),
    );
  }
}

/// 周 / 月聚合视图。
final class RangeView {
  const RangeView({
    required this.start,
    required this.end,
    required this.days,
    required this.totals,
  });

  final DateTime start;
  final DateTime end;
  final List<DaySummary> days;
  final DayTotals totals;

  factory RangeView.fromJson(Map<String, dynamic> json) {
    return RangeView(
      start: DateTime.parse(json['start'] as String),
      end: DateTime.parse(json['end'] as String),
      days: (json['days'] as List? ?? const [])
          .map((day) => DaySummary.fromJson(day as Map<String, dynamic>))
          .toList(growable: false),
      totals: DayTotals.fromJson(
        (json['totals'] as Map<String, dynamic>?) ?? const {},
      ),
    );
  }
}

/// 未完成汇总中的一项。
final class PendingItem {
  const PendingItem({
    required this.todo,
    required this.overdueDays,
    required this.severe,
  });

  final TodoItem todo;
  final int overdueDays;

  /// 逾期 3 天及以上为高危分组。
  final bool severe;

  factory PendingItem.fromJson(Map<String, dynamic> json) {
    return PendingItem(
      todo: TodoItem.fromJson(json['todo'] as Map<String, dynamic>),
      overdueDays: (json['overdueDays'] as num?)?.toInt() ?? 0,
      severe: (json['bucket'] as String? ?? '') == 'THREE_DAYS_OR_MORE',
    );
  }
}

/// 未完成汇总。
final class PendingSummary {
  const PendingSummary({
    required this.total,
    required this.countByType,
    required this.items,
  });

  final int total;
  final Map<TodoType, int> countByType;
  final List<PendingItem> items;

  factory PendingSummary.fromJson(Map<String, dynamic> json) {
    final raw = (json['countByType'] as Map?) ?? const {};
    final counts = <TodoType, int>{};
    raw.forEach((key, value) {
      counts[TodoType.parse(key.toString())] = (value as num).toInt();
    });
    return PendingSummary(
      total: (json['total'] as num?)?.toInt() ?? 0,
      countByType: counts,
      items: (json['items'] as List? ?? const [])
          .map((item) => PendingItem.fromJson(item as Map<String, dynamic>))
          .toList(growable: false),
    );
  }

  static const empty = PendingSummary(total: 0, countByType: {}, items: []);
}

/// 服务端裁决的进度结果。
final class ProgressResult {
  const ProgressResult({
    required this.status,
    required this.positionMs,
    required this.watchedMs,
    required this.progressPermille,
    required this.completed,
    this.targetProgressPermille,
  });

  final TodoStatus status;
  final int positionMs;
  final int watchedMs;
  final int progressPermille;
  final bool completed;
  final int? targetProgressPermille;

  factory ProgressResult.fromJson(Map<String, dynamic> json) {
    return ProgressResult(
      status: TodoStatus.parse(json['status'] as String),
      positionMs: (json['positionMs'] as num?)?.toInt() ?? 0,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      progressPermille: (json['progressPermille'] as num?)?.toInt() ?? 0,
      completed: json['completed'] as bool? ?? false,
      targetProgressPermille: (json['targetProgressPermille'] as num?)?.toInt(),
    );
  }
}

/// 课程库筛选维度取值。
final class FacetValue {
  const FacetValue({required this.value, required this.courseCount});

  final String value;
  final int courseCount;

  factory FacetValue.fromJson(Map<String, dynamic> json) {
    return FacetValue(
      value: json['value'] as String,
      courseCount: (json['courseCount'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 课程库筛选维度集合，全部来自 Emby 元数据。
final class CatalogFacets {
  const CatalogFacets({
    required this.genres,
    required this.tags,
    required this.people,
    required this.years,
  });

  final List<FacetValue> genres;
  final List<FacetValue> tags;
  final List<FacetValue> people;
  final List<FacetValue> years;

  factory CatalogFacets.fromJson(Map<String, dynamic> json) {
    List<FacetValue> parse(String key) => (json[key] as List? ?? const [])
        .map((item) => FacetValue.fromJson(item as Map<String, dynamic>))
        .toList(growable: false);
    return CatalogFacets(
      genres: parse('genres'),
      tags: parse('tags'),
      people: parse('people'),
      years: parse('years'),
    );
  }

  static const empty = CatalogFacets(
    genres: [],
    tags: [],
    people: [],
    years: [],
  );
}

/// 课程卡片。
final class CourseSummary {
  const CourseSummary({
    required this.id,
    required this.title,
    required this.overview,
    required this.genres,
    required this.tags,
    required this.people,
    required this.resourceCount,
    required this.totalDurationMs,
    required this.completedCount,
    required this.watchedMs,
    required this.completedPercent,
    this.productionYear,
  });

  final String id;
  final String title;
  final String overview;
  final int? productionYear;
  final List<String> genres;
  final List<String> tags;
  final List<String> people;
  final int resourceCount;
  final int totalDurationMs;
  final int completedCount;
  final int watchedMs;
  final int completedPercent;

  String get primaryGenre => genres.isEmpty ? '未分类' : genres.first;

  factory CourseSummary.fromJson(Map<String, dynamic> json) {
    return CourseSummary(
      id: json['id'] as String,
      title: json['title'] as String,
      overview: json['overview'] as String? ?? '',
      productionYear: (json['productionYear'] as num?)?.toInt(),
      genres: _stringList(json['genres']),
      tags: _stringList(json['tags']),
      people: _peopleNames(json['people']),
      resourceCount: (json['resourceCount'] as num?)?.toInt() ?? 0,
      totalDurationMs: (json['totalDurationMs'] as num?)?.toInt() ?? 0,
      completedCount: (json['completedCount'] as num?)?.toInt() ?? 0,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      completedPercent: (json['completedPercent'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 课程下的一个资源。
final class CourseResource {
  const CourseResource({
    required this.id,
    required this.resourceType,
    required this.title,
    required this.sortIndex,
    required this.maxPositionMs,
    required this.watchedMs,
    required this.progressPermille,
    required this.completedBefore,
    required this.measurable,
    this.durationMs,
    this.pageCount,
  });

  final String id;
  final ResourceType resourceType;
  final String title;
  final int sortIndex;
  final int? durationMs;
  final int? pageCount;
  final int maxPositionMs;
  final int watchedMs;
  final int progressPermille;
  final bool completedBefore;
  final bool measurable;

  factory CourseResource.fromJson(Map<String, dynamic> json) {
    return CourseResource(
      id: json['id'] as String,
      resourceType: ResourceType.parse(json['resourceType'] as String?),
      title: json['title'] as String,
      sortIndex: (json['sortIndex'] as num?)?.toInt() ?? 0,
      durationMs: (json['durationMs'] as num?)?.toInt(),
      pageCount: (json['pageCount'] as num?)?.toInt(),
      maxPositionMs: (json['maxPositionMs'] as num?)?.toInt() ?? 0,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      progressPermille: (json['progressPermille'] as num?)?.toInt() ?? 0,
      completedBefore: json['completedBefore'] as bool? ?? false,
      measurable: json['measurable'] as bool? ?? false,
    );
  }
}

/// 课程详情。
final class CourseDetail {
  const CourseDetail({
    required this.summary,
    required this.genres,
    required this.tags,
    required this.people,
    required this.resources,
  });

  final CourseSummary summary;
  final List<String> genres;
  final List<String> tags;
  final List<String> people;
  final List<CourseResource> resources;

  factory CourseDetail.fromJson(Map<String, dynamic> json) {
    return CourseDetail(
      summary: CourseSummary.fromJson(json['summary'] as Map<String, dynamic>),
      genres: _stringList(json['genres']),
      tags: _stringList(json['tags']),
      people: _peopleNames(json['people']),
      resources: (json['resources'] as List? ?? const [])
          .map((item) => CourseResource.fromJson(item as Map<String, dynamic>))
          .toList(growable: false),
    );
  }
}

/// 统计单日数据。
final class StatsDay {
  const StatsDay({
    required this.date,
    required this.total,
    required this.done,
    required this.backfilled,
    required this.watchedMs,
    required this.focusedMs,
  });

  final DateTime date;
  final int total;
  final int done;
  final int backfilled;
  final int watchedMs;
  final int focusedMs;

  int get totalMs => watchedMs + focusedMs;

  factory StatsDay.fromJson(Map<String, dynamic> json) {
    return StatsDay(
      date: DateTime.parse(json['date'] as String),
      total: (json['total'] as num?)?.toInt() ?? 0,
      done: (json['done'] as num?)?.toInt() ?? 0,
      backfilled: (json['backfilled'] as num?)?.toInt() ?? 0,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      focusedMs: (json['focusedMs'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 统计时段分布。
final class StatsHour {
  const StatsHour({
    required this.hour,
    required this.watchedMs,
    required this.focusedMs,
  });

  final int hour;
  final int watchedMs;
  final int focusedMs;

  factory StatsHour.fromJson(Map<String, dynamic> json) {
    return StatsHour(
      hour: (json['hour'] as num).toInt(),
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      focusedMs: (json['focusedMs'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 排行条目。
final class RankingEntry {
  const RankingEntry({
    required this.name,
    required this.watchedMs,
    required this.lessonCount,
  });

  final String name;
  final int watchedMs;
  final int lessonCount;

  factory RankingEntry.fromJson(Map<String, dynamic> json) {
    return RankingEntry(
      name: json['name'] as String,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      lessonCount: (json['lessonCount'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 数据 Tab 的完整统计视图。
final class StatsView {
  const StatsView({
    required this.range,
    required this.start,
    required this.end,
    required this.totalTodos,
    required this.doneTodos,
    required this.backfilledTodos,
    required this.watchedMs,
    required this.focusedMs,
    required this.focusFinished,
    required this.focusAbandoned,
    required this.days,
    required this.hours,
    required this.courseRanking,
    required this.personRanking,
    required this.genreRanking,
    required this.noteTagCounts,
    required this.deletionCounts,
  });

  final String range;
  final DateTime start;
  final DateTime end;
  final int totalTodos;
  final int doneTodos;
  final int backfilledTodos;
  final int watchedMs;
  final int focusedMs;
  final int focusFinished;
  final int focusAbandoned;
  final List<StatsDay> days;
  final List<StatsHour> hours;
  final List<RankingEntry> courseRanking;
  final List<RankingEntry> personRanking;
  final List<RankingEntry> genreRanking;
  final Map<NoteTag, int> noteTagCounts;
  final Map<String, int> deletionCounts;

  int get totalMs => watchedMs + focusedMs;

  int get focusCompletionPercent {
    final total = focusFinished + focusAbandoned;
    if (total == 0) return 0;
    return focusFinished * 100 ~/ total;
  }

  factory StatsView.fromJson(Map<String, dynamic> json) {
    final rankings = (json['rankings'] as Map<String, dynamic>?) ?? const {};
    List<RankingEntry> ranking(String key) =>
        (rankings[key] as List? ?? const [])
            .map((item) => RankingEntry.fromJson(item as Map<String, dynamic>))
            .toList(growable: false);
    final noteTags = <NoteTag, int>{};
    for (final item in (json['noteTags'] as List? ?? const [])) {
      final map = item as Map<String, dynamic>;
      final tag = NoteTag.tryParse(map['tag'].toString());
      if (tag != null) noteTags[tag] = (map['count'] as num).toInt();
    }
    final deletions = <String, int>{};
    for (final item in (json['deletions'] as List? ?? const [])) {
      final map = item as Map<String, dynamic>;
      deletions[map['reasonTag'].toString()] = (map['count'] as num).toInt();
    }
    return StatsView(
      range: json['range'] as String? ?? 'DAY',
      start: DateTime.parse(json['start'] as String),
      end: DateTime.parse(json['end'] as String),
      totalTodos: (json['totalTodos'] as num?)?.toInt() ?? 0,
      doneTodos: (json['doneTodos'] as num?)?.toInt() ?? 0,
      backfilledTodos: (json['backfilledTodos'] as num?)?.toInt() ?? 0,
      watchedMs: (json['watchedMs'] as num?)?.toInt() ?? 0,
      focusedMs: (json['focusedMs'] as num?)?.toInt() ?? 0,
      focusFinished: (json['focusFinished'] as num?)?.toInt() ?? 0,
      focusAbandoned: (json['focusAbandoned'] as num?)?.toInt() ?? 0,
      days: (json['days'] as List? ?? const [])
          .map((day) => StatsDay.fromJson(day as Map<String, dynamic>))
          .toList(growable: false),
      hours: (json['hours'] as List? ?? const [])
          .map((hour) => StatsHour.fromJson(hour as Map<String, dynamic>))
          .toList(growable: false),
      courseRanking: ranking('courses'),
      personRanking: ranking('people'),
      genreRanking: ranking('genres'),
      noteTagCounts: noteTags,
      deletionCounts: deletions,
    );
  }
}

/// 待回应催办。
final class PendingNag {
  const PendingNag({
    this.title,
    required this.id,
    required this.message,
    required this.idleMinutes,
    required this.pendingCount,
    required this.requireReason,
  });

  final String? title;
  final String id;
  final String message;
  final int idleMinutes;
  final int pendingCount;
  final bool requireReason;

  factory PendingNag.fromJson(Map<String, dynamic> json) {
    return PendingNag(
      title: json['title'] as String?,
      id: json['id'] as String,
      message: json['message'] as String? ?? '',
      idleMinutes: (json['idleMinutes'] as num?)?.toInt() ?? 0,
      pendingCount: (json['pendingCount'] as num?)?.toInt() ?? 0,
      requireReason: json['requireReason'] as bool? ?? true,
    );
  }
}

/// 心跳响应。
final class HeartbeatResult {
  const HeartbeatResult({
    required this.heartbeatIntervalSeconds,
    required this.minReasonLength,
    this.nagTransportMode = 'HEARTBEAT',
    this.pendingNagId,
    this.pendingNagMessage,
    this.requireReason = true,
  });

  final int heartbeatIntervalSeconds;
  final int minReasonLength;
  final String nagTransportMode;
  final String? pendingNagId;
  final String? pendingNagMessage;
  final bool requireReason;

  factory HeartbeatResult.fromJson(Map<String, dynamic> json) {
    return HeartbeatResult(
      heartbeatIntervalSeconds:
          (json['heartbeatIntervalSeconds'] as num?)?.toInt() ?? 60,
      minReasonLength: (json['minReasonLength'] as num?)?.toInt() ?? 5,
      nagTransportMode: json['nagTransportMode'] as String? ?? 'HEARTBEAT',
      pendingNagId: json['pendingNagId'] as String?,
      pendingNagMessage: json['pendingNagMessage'] as String?,
      requireReason: json['requireReason'] as bool? ?? true,
    );
  }
}

/// 当前用户的完整设置视图（催办策略只读）。
final class MeSettings {
  const MeSettings({
    required this.profile,
    required this.supervisors,
    required this.heartbeatIntervalSeconds,
    required this.firstThresholdMinutes,
    required this.repeatIntervalMinutes,
    required this.dailyMax,
    required this.quietStart,
    required this.quietEnd,
    required this.minReasonLength,
    required this.documentResources,
  });

  final UserSummary profile;
  final List<SupervisorRef> supervisors;
  final int heartbeatIntervalSeconds;
  final int firstThresholdMinutes;
  final int repeatIntervalMinutes;
  final int dailyMax;
  final String quietStart;
  final String quietEnd;
  final int minReasonLength;
  final bool documentResources;

  factory MeSettings.fromJson(Map<String, dynamic> json) {
    final policy = (json['nagPolicy'] as Map<String, dynamic>?) ?? const {};
    final features = (json['features'] as Map<String, dynamic>?) ?? const {};
    return MeSettings(
      profile: UserSummary.fromJson(json),
      supervisors: (json['supervisors'] as List? ?? const [])
          .map((item) => SupervisorRef.fromJson(item as Map<String, dynamic>))
          .toList(growable: false),
      heartbeatIntervalSeconds:
          (policy['heartbeatIntervalSeconds'] as num?)?.toInt() ?? 60,
      firstThresholdMinutes:
          (policy['firstThresholdMinutes'] as num?)?.toInt() ?? 90,
      repeatIntervalMinutes:
          (policy['repeatIntervalMinutes'] as num?)?.toInt() ?? 60,
      dailyMax: (policy['dailyMax'] as num?)?.toInt() ?? 3,
      quietStart: policy['quietStart'] as String? ?? '23:30',
      quietEnd: policy['quietEnd'] as String? ?? '07:00',
      minReasonLength: (policy['minReasonLength'] as num?)?.toInt() ?? 5,
      documentResources: features['documentResources'] as bool? ?? false,
    );
  }
}

/// 用户摘要。
final class UserSummary {
  const UserSummary({
    required this.id,
    required this.username,
    required this.displayName,
    required this.timezone,
    required this.roles,
    required this.supervisorMode,
    required this.learnerCount,
  });

  final String id;
  final String username;
  final String displayName;
  final String timezone;
  final List<String> roles;
  final bool supervisorMode;
  final int learnerCount;

  factory UserSummary.fromJson(Map<String, dynamic> json) {
    return UserSummary(
      id: json['id'] as String,
      username: json['username'] as String,
      displayName: json['displayName'] as String? ?? json['username'] as String,
      timezone: json['timezone'] as String? ?? 'Asia/Shanghai',
      roles: _stringList(json['roles']),
      supervisorMode: json['supervisorMode'] as bool? ?? false,
      learnerCount: (json['learnerCount'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 我的督学人。
final class SupervisorRef {
  const SupervisorRef({
    required this.userId,
    required this.displayName,
    required this.kind,
    required this.canNag,
  });

  final String userId;
  final String displayName;
  final String kind;
  final bool canNag;

  bool get isPrimary => kind == 'PRIMARY';

  factory SupervisorRef.fromJson(Map<String, dynamic> json) {
    return SupervisorRef(
      userId: json['userId'] as String,
      displayName: json['displayName'] as String? ?? '督学人',
      kind: json['kind'] as String? ?? 'PRIMARY',
      canNag: json['canNag'] as bool? ?? false,
    );
  }
}

/// 督学端学员总览行。
/// 服务端当前活动展示；离线和后台标识与页面并列，避免把历史快照当实时播放。
final class CurrentAppActivity {
  const CurrentAppActivity({
    this.pageLabel = '未知页面',
    this.stateLabel = '未知状态',
    this.todoTitle,
    this.updatedAt,
    this.background = false,
    this.stale = true,
  });
  final String pageLabel;
  final String stateLabel;
  final String? todoTitle;
  final DateTime? updatedAt;
  final bool background;
  final bool stale;
  String get summary => [
    if (stale) '最后上报',
    if (background) 'App 已切后台',
    pageLabel,
    stateLabel,
    ?todoTitle,
  ].join(' · ');
  factory CurrentAppActivity.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) return const CurrentAppActivity();
    return CurrentAppActivity(
      pageLabel: value['pageLabel'] as String? ?? '未知页面',
      stateLabel: value['stateLabel'] as String? ?? '未知状态',
      todoTitle: value['todoTitle'] as String?,
      updatedAt: DateTime.tryParse(value['updatedAt'] as String? ?? ''),
      background: value['background'] as bool? ?? false,
      stale: value['stale'] as bool? ?? true,
    );
  }
}

final class LearnerOverview {
  const LearnerOverview({
    required this.userId,
    required this.username,
    required this.displayName,
    required this.presenceState,
    this.activity = const CurrentAppActivity(),
    required this.idleMinutes,
    required this.todayTotal,
    required this.todayDone,
    required this.todayWatchedMs,
    required this.todayFocusedMs,
    required this.monthlyDeletions,
    required this.unansweredNags,
    required this.alerts,
    required this.canNag,
  });

  final String userId;
  final String username;
  final String displayName;
  final PresenceState presenceState;
  final CurrentAppActivity activity;
  final int idleMinutes;
  final int todayTotal;
  final int todayDone;
  final int todayWatchedMs;
  final int todayFocusedMs;
  final int monthlyDeletions;
  final int unansweredNags;
  final List<String> alerts;
  final bool canNag;

  bool get zeroDone => alerts.contains('ZERO_DONE');

  factory LearnerOverview.fromJson(Map<String, dynamic> json) {
    return LearnerOverview(
      userId: json['userId'] as String,
      username: json['username'] as String,
      displayName: json['displayName'] as String? ?? json['username'] as String,
      presenceState: PresenceState.parse(
        json['presenceState'] as String? ?? 'OFFLINE',
      ),
      activity: CurrentAppActivity.fromJson(json['activity']),
      idleMinutes: (json['idleMinutes'] as num?)?.toInt() ?? 0,
      todayTotal: (json['todayTotal'] as num?)?.toInt() ?? 0,
      todayDone: (json['todayDone'] as num?)?.toInt() ?? 0,
      todayWatchedMs: (json['todayWatchedMs'] as num?)?.toInt() ?? 0,
      todayFocusedMs: (json['todayFocusedMs'] as num?)?.toInt() ?? 0,
      monthlyDeletions: (json['monthlyDeletions'] as num?)?.toInt() ?? 0,
      unansweredNags: (json['unansweredNags'] as num?)?.toInt() ?? 0,
      alerts: _stringList(json['alerts']),
      canNag: json['canNag'] as bool? ?? false,
    );
  }
}

/// 督学端学员详情。
final class LearnerDetail {
  const LearnerDetail({
    required this.userId,
    required this.username,
    required this.displayName,
    required this.presenceState,
    this.activity = const CurrentAppActivity(),
    required this.idleMinutes,
    required this.day,
    required this.deletions,
    required this.stats,
  });

  final String userId;
  final String username;
  final String displayName;
  final PresenceState presenceState;
  final CurrentAppActivity activity;
  final int idleMinutes;
  final DayView day;
  final List<LearnerDeletion> deletions;
  final StatsView stats;

  factory LearnerDetail.fromJson(Map<String, dynamic> json) {
    final presence = (json['presence'] as Map<String, dynamic>?) ?? const {};
    return LearnerDetail(
      userId: json['userId'] as String,
      username: json['username'] as String,
      displayName: json['displayName'] as String? ?? json['username'] as String,
      presenceState: PresenceState.parse(
        presence['state'] as String? ?? 'OFFLINE',
      ),
      activity: CurrentAppActivity.fromJson(presence['activity']),
      idleMinutes: (presence['idleMinutes'] as num?)?.toInt() ?? 0,
      day: DayView.fromJson(json['day'] as Map<String, dynamic>),
      deletions: (json['deletions'] as List? ?? const [])
          .map((item) => LearnerDeletion.fromJson(item as Map<String, dynamic>))
          .toList(growable: false),
      stats: StatsView.fromJson(json['stats'] as Map<String, dynamic>),
    );
  }
}

/// 学员删除记录。
final class LearnerDeletion {
  const LearnerDeletion({
    required this.title,
    required this.reasonTag,
    required this.reasonText,
    required this.deletedAt,
  });

  final String title;
  final String reasonTag;
  final String reasonText;
  final DateTime deletedAt;

  factory LearnerDeletion.fromJson(Map<String, dynamic> json) {
    return LearnerDeletion(
      title: json['titleSnapshot'] as String? ?? '',
      reasonTag: json['reasonTag'] as String? ?? '',
      reasonText: json['reasonText'] as String? ?? '',
      deletedAt: DateTime.parse(json['deletedAt'] as String),
    );
  }
}

/// 督学端时间线条目。
final class SupervisorFeedItem {
  const SupervisorFeedItem({
    required this.kind,
    required this.learnerUserId,
    required this.occurredAt,
    required this.title,
    required this.tag,
    required this.status,
    required this.reasonText,
    this.sentByMe = false,
  });

  final String kind;
  final String learnerUserId;
  final DateTime occurredAt;
  final String title;
  final String tag;
  final String status;
  final String reasonText;

  /// 由服务端按实际发起人判定，管理员和其他督学的记录不得归入「我发的」。
  final bool sentByMe;

  bool get isDeletion => kind == 'DELETION';

  factory SupervisorFeedItem.fromJson(Map<String, dynamic> json) {
    return SupervisorFeedItem(
      kind: json['kind'] as String? ?? 'NAG',
      learnerUserId: json['learnerUserId'] as String? ?? '',
      occurredAt: DateTime.parse(json['occurredAt'] as String),
      title: json['title'] as String? ?? '',
      tag: json['tag'] as String? ?? '',
      status: json['status'] as String? ?? '',
      reasonText: json['reasonText'] as String? ?? '',
      sentByMe: json['sentByMe'] as bool? ?? false,
    );
  }
}

/// 督学端学员报告。
final class LearnerReport {
  const LearnerReport({
    required this.userId,
    required this.username,
    required this.displayName,
    required this.stats,
    required this.nagCount,
    required this.nagRespondedCount,
  });

  final String userId;
  final String username;
  final String displayName;
  final StatsView stats;
  final int nagCount;
  final int nagRespondedCount;

  factory LearnerReport.fromJson(Map<String, dynamic> json) {
    return LearnerReport(
      userId: json['userId'] as String,
      username: json['username'] as String,
      displayName: json['displayName'] as String? ?? json['username'] as String,
      stats: StatsView.fromJson(json['stats'] as Map<String, dynamic>),
      nagCount: (json['nagCount'] as num?)?.toInt() ?? 0,
      nagRespondedCount: (json['nagRespondedCount'] as num?)?.toInt() ?? 0,
    );
  }
}

List<String> _stringList(Object? raw) {
  if (raw is List) {
    return raw.map((item) => item.toString()).toList(growable: false);
  }
  return const [];
}

List<String> _peopleNames(Object? raw) {
  if (raw is List) {
    return raw
        .map((item) {
          if (item is Map) return (item['name'] ?? '').toString();
          return item.toString();
        })
        .where((name) => name.isNotEmpty)
        .toList(growable: false);
  }
  return const [];
}
