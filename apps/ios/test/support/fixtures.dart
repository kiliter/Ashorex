import 'package:shangan_ios/core/models/shangan_models.dart';

/// 测试夹具：构造与服务端 DTO 同构的 JSON，避免每个测试重复拼装。
///
/// 只提供最少必要字段，其余走模型自带的默认值，这样字段缺失时测试仍能反映
/// 真实的解析行为。
Map<String, Object?> todoJson({
  required String id,
  String title = '课时',
  String todoType = 'COURSE',
  String status = 'TODO',
  String localDate = '2026-09-07',
  int sortOrder = 0,
  int progressPermille = 0,
  int? targetProgressPermille,
  int watchedMs = 0,
  int focusedMs = 0,
  String focusState = 'IDLE',
  int? plannedSeconds,
  bool requireEvidence = false,
  int attachmentCount = 0,
  String note = '',
  List<String> noteTags = const [],
  String? resourceId,
  int? resourceDurationMs,
  bool resourceAvailable = true,
}) {
  return {
    'id': id,
    'localDate': localDate,
    'todoType': todoType,
    'title': title,
    'note': note,
    'sortOrder': sortOrder,
    'status': status,
    'progressPermille': progressPermille,
    'targetProgressPermille': targetProgressPermille,
    'watchedMs': watchedMs,
    'focusState': focusState,
    'focusedMs': focusedMs,
    'plannedSeconds': plannedSeconds,
    'requireEvidence': requireEvidence,
    'attachmentCount': attachmentCount,
    'noteTags': noteTags,
    'resourceId': resourceId,
    'resourceDurationMs': resourceDurationMs,
    'resourceAvailable': resourceAvailable,
  };
}

Map<String, Object?> dayViewJson({
  String date = '2026-09-07',
  bool today = true,
  bool history = false,
  List<Map<String, Object?>> todos = const [],
  int deletionCount = 0,
  Map<String, Object?>? totals,
}) {
  final done = todos.where((todo) => todo['status'] == 'DONE').length;
  return {
    'date': date,
    'today': today,
    'history': history,
    'todos': todos,
    'deletionCount': deletionCount,
    'totals':
        totals ??
        {
          'total': todos.length,
          'done': done,
          'watchedMs': 0,
          'focusedMs': 0,
          'attachmentCount': 0,
        },
  };
}

Map<String, Object?> daySummaryJson({
  required String date,
  int total = 0,
  int done = 0,
  String outcome = 'NO_TODOS',
  bool today = false,
  int watchedMs = 0,
  int focusedMs = 0,
}) {
  return {
    'date': date,
    'today': today,
    'total': total,
    'done': done,
    'pending': total - done,
    'watchedMs': watchedMs,
    'focusedMs': focusedMs,
    'countByType': const {},
    'outcome': outcome,
  };
}

Map<String, Object?> rangeViewJson({
  required String start,
  required String end,
  List<Map<String, Object?>> days = const [],
}) {
  return {
    'start': start,
    'end': end,
    'days': days,
    'totals': {
      'total': days.fold<int>(0, (sum, day) => sum + (day['total'] as int)),
      'done': days.fold<int>(0, (sum, day) => sum + (day['done'] as int)),
      'watchedMs': 0,
      'focusedMs': 0,
      'attachmentCount': 0,
    },
  };
}

Map<String, Object?> goalJson({
  required String id,
  String name = '法考客观题',
  String examDate = '2026-09-20',
  int daysRemaining = 13,
  String urgency = 'NORMAL',
  bool primary = true,
  String note = '',
}) {
  return {
    'id': id,
    'name': name,
    'examDate': examDate,
    'note': note,
    'primary': primary,
    'daysRemaining': daysRemaining,
    'urgency': urgency,
  };
}

Map<String, Object?> statsJson({
  String range = 'DAY',
  String start = '2026-09-07',
  String end = '2026-09-07',
  int totalTodos = 0,
  int doneTodos = 0,
  int watchedMs = 0,
  int focusedMs = 0,
  int focusFinished = 0,
  int focusAbandoned = 0,
  List<Map<String, Object?>> days = const [],
  List<Map<String, Object?>> hours = const [],
  Map<String, Object?>? rankings,
  List<Map<String, Object?>> noteTags = const [],
  List<Map<String, Object?>> deletions = const [],
}) {
  return {
    'range': range,
    'start': start,
    'end': end,
    'totalTodos': totalTodos,
    'doneTodos': doneTodos,
    'backfilledTodos': 0,
    'watchedMs': watchedMs,
    'focusedMs': focusedMs,
    'focusFinished': focusFinished,
    'focusAbandoned': focusAbandoned,
    'days': days,
    'hours': hours,
    'rankings': rankings ?? const {'courses': [], 'people': [], 'genres': []},
    'noteTags': noteTags,
    'deletions': deletions,
  };
}

Map<String, Object?> meJson({
  String id = 'u-1',
  String username = 'demo',
  String displayName = '张三',
  String timezone = 'Asia/Shanghai',
  List<String> roles = const ['LEARNER'],
  bool supervisorMode = false,
  int learnerCount = 0,
  List<Map<String, Object?>> supervisors = const [],
  Map<String, Object?>? nagPolicy,
  bool documentResources = false,
}) {
  return {
    'id': id,
    'username': username,
    'displayName': displayName,
    'timezone': timezone,
    'roles': roles,
    'supervisorMode': supervisorMode,
    'learnerCount': learnerCount,
    'supervisors': supervisors,
    'nagPolicy':
        nagPolicy ??
        const {
          'heartbeatIntervalSeconds': 60,
          'firstThresholdMinutes': 90,
          'repeatIntervalMinutes': 60,
          'dailyMax': 3,
          'quietStart': '23:30',
          'quietEnd': '07:00',
          'minReasonLength': 5,
        },
    'features': {'documentResources': documentResources},
  };
}

Map<String, Object?> courseSummaryJson({
  required String id,
  String title = '行政法专题',
  List<String> genres = const ['法律'],
  List<String> tags = const [],
  List<String> people = const [],
  int resourceCount = 2,
  int totalDurationMs = 3600000,
  int completedCount = 0,
  int watchedMs = 0,
  int completedPercent = 0,
  int? productionYear = 2026,
  String overview = '',
}) {
  return {
    'id': id,
    'title': title,
    'overview': overview,
    'productionYear': productionYear,
    'genres': genres,
    'tags': tags,
    'people': people,
    'resourceCount': resourceCount,
    'totalDurationMs': totalDurationMs,
    'completedCount': completedCount,
    'watchedMs': watchedMs,
    'completedPercent': completedPercent,
  };
}

Map<String, Object?> courseDetailJson({
  required String id,
  String title = '行政法专题',
  List<Map<String, Object?>> resources = const [],
}) {
  return {
    'summary': courseSummaryJson(id: id, title: title),
    'genres': const ['法律'],
    'tags': const [],
    'people': const [],
    'resources': resources,
  };
}

Map<String, Object?> courseResourceJson({
  required String id,
  String title = '第 01 讲',
  int sortIndex = 1,
  int? durationMs = 1800000,
  int maxPositionMs = 0,
  int watchedMs = 0,
  int progressPermille = 0,
  bool completedBefore = false,
  bool measurable = true,
}) {
  return {
    'id': id,
    'resourceType': 'VIDEO',
    'title': title,
    'sortIndex': sortIndex,
    'durationMs': durationMs,
    'maxPositionMs': maxPositionMs,
    'watchedMs': watchedMs,
    'progressPermille': progressPermille,
    'completedBefore': completedBefore,
    'measurable': measurable,
  };
}

Map<String, Object?> learnerOverviewJson({
  required String userId,
  String username = 'lisi',
  String displayName = '李四',
  String presenceState = 'ONLINE',
  int idleMinutes = 0,
  int todayTotal = 3,
  int todayDone = 1,
  int monthlyDeletions = 0,
  int unansweredNags = 0,
  List<String> alerts = const [],
  bool canNag = true,
}) {
  return {
    'userId': userId,
    'username': username,
    'displayName': displayName,
    'presenceState': presenceState,
    'idleMinutes': idleMinutes,
    'todayTotal': todayTotal,
    'todayDone': todayDone,
    'todayWatchedMs': 0,
    'todayFocusedMs': 0,
    'monthlyDeletions': monthlyDeletions,
    'unansweredNags': unansweredNags,
    'alerts': alerts,
    'canNag': canNag,
  };
}

/// 直接构造模型对象，供只需要渲染而不需要网络的 widget 测试使用。
TodoItem todoItem({
  required String id,
  String title = '课时',
  TodoType type = TodoType.course,
  TodoStatus status = TodoStatus.todo,
  int progressPermille = 0,
  int? targetProgressPermille,
  int watchedMs = 0,
  int focusedMs = 0,
  FocusState focusState = FocusState.idle,
  int? plannedSeconds,
  bool requireEvidence = false,
  int attachmentCount = 0,
  String note = '',
  List<NoteTag> noteTags = const [],
}) {
  return TodoItem(
    id: id,
    localDate: DateTime(2026, 9, 7),
    todoType: type,
    title: title,
    note: note,
    sortOrder: 0,
    status: status,
    progressPermille: progressPermille,
    progressPositionMs: 0,
    watchedMs: watchedMs,
    focusState: focusState,
    focusedMs: focusedMs,
    requireEvidence: requireEvidence,
    backfilled: false,
    attachmentCount: attachmentCount,
    noteTags: noteTags,
    targetProgressPermille: targetProgressPermille,
    plannedSeconds: plannedSeconds,
  );
}
