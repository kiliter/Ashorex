import 'package:shangan_ios/core/presence/app_activity.dart';
import 'dart:typed_data';
import 'package:shangan_ios/core/models/course_addition_models.dart';

import 'package:shangan_ios/core/api/api_client.dart';

import 'package:shangan_ios/core/models/shangan_models.dart';

/// 上岸 V2 的统一数据访问层。
///
/// 页面与控制器只依赖这里，不直接调用 Dio。所有业务判定都由服务端裁决，
/// 这里只做 JSON 与模型之间的转换。
final class ShanganRepository {
  ShanganRepository(this._api);

  final ApiClient _api;

  /// 播放只能使用服务端代理，Emby 凭据始终留在服务端。
  Future<({Uri uri, Map<String, String> headers})> playbackSource(
    String resourceId,
  ) => _api.playbackSource(resourceId);

  // ---------------- 我的 ----------------

  Future<MeSettings> loadMe() async {
    return MeSettings.fromJson(await _api.getJson('/api/v1/me'));
  }

  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    await _api.patchEmpty(
      '/api/v1/me/password',
      data: {'currentPassword': currentPassword, 'newPassword': newPassword},
    );
  }

  Future<MeSettings> changeTimezone(String timezone) async {
    return MeSettings.fromJson(
      await _api.patchJson('/api/v1/me/timezone', data: {'timezone': timezone}),
    );
  }

  /// 个人推送配置通过当前会话确定归属，不接受其他用户 ID。
  Future<Map<String, dynamic>> loadBarkSettings() =>
      _api.getJson('/api/v1/me/bark');
  Future<void> saveBarkSettings({
    required String baseUrl,
    required String deviceKey,
    required bool enabled,
  }) async {
    await _api.putJson(
      '/api/v1/me/bark',
      data: {'baseUrl': baseUrl, 'deviceKey': deviceKey, 'enabled': enabled},
    );
  }

  // ---------------- 考试目标 ----------------

  Future<List<ExamGoal>> loadGoals() async {
    final list = await _api.getJsonList('/api/v1/exam-goals');
    return list.map(ExamGoal.fromJson).toList(growable: false);
  }

  Future<ExamGoal> createGoal({
    required String name,
    required DateTime examDate,
    String note = '',
    bool primary = false,
  }) async {
    return ExamGoal.fromJson(
      await _api.postJson(
        '/api/v1/exam-goals',
        data: {
          'name': name,
          'examDate': _formatDate(examDate),
          'note': note,
          'primary': primary,
        },
      ),
    );
  }

  Future<ExamGoal> updateGoal({
    required String goalId,
    String? name,
    DateTime? examDate,
    String? note,
    bool? primary,
  }) async {
    return ExamGoal.fromJson(
      await _api.patchJson(
        '/api/v1/exam-goals/$goalId',
        data: {
          'name': ?name,
          if (examDate != null) 'examDate': _formatDate(examDate),
          'note': ?note,
          'primary': ?primary,
        },
      ),
    );
  }

  Future<void> deleteGoal(String goalId) async {
    await _api.deleteEmpty('/api/v1/exam-goals/$goalId');
  }

  // ---------------- Todo 视图 ----------------

  Future<DayView> loadDay({DateTime? date}) async {
    final query = date == null ? '' : '&date=${_formatDate(date)}';
    return DayView.fromJson(await _api.getJson('/api/v1/todos?view=DAY$query'));
  }

  Future<RangeView> loadWeek({DateTime? weekStart}) async {
    final query = weekStart == null
        ? ''
        : '&weekStart=${_formatDate(weekStart)}';
    return RangeView.fromJson(
      await _api.getJson('/api/v1/todos?view=WEEK$query'),
    );
  }

  Future<RangeView> loadMonth({DateTime? month}) async {
    final query = month == null
        ? ''
        : '&month=${month.year.toString().padLeft(4, '0')}-${month.month.toString().padLeft(2, '0')}';
    return RangeView.fromJson(
      await _api.getJson('/api/v1/todos?view=MONTH$query'),
    );
  }

  Future<PendingSummary> loadPendingSummary() async {
    return PendingSummary.fromJson(
      await _api.getJson('/api/v1/todos/pending-summary'),
    );
  }

  // ---------------- Todo 写操作 ----------------

  Future<void> createTodos(List<Map<String, Object?>> items) async {
    await _api.postEmpty('/api/v1/todos', data: {'items': items});
  }

  /// 添加前只读预览同日重复与历史未完成项。
  Future<List<CourseAdditionItem>> previewCourseAdditions(
    List<Map<String, Object?>> items,
  ) async {
    final result = await _api.postJson(
      '/api/v1/todos/course-additions/preview',
      data: {'items': items},
    );
    return (result['items'] as List<dynamic>)
        .map(
          (item) => CourseAdditionItem.fromJson(item as Map<String, dynamic>),
        )
        .toList();
  }

  /// 仅提交用户确认的原待办 ID，服务端在事务内再次判断并返回真实计数。
  Future<CourseAdditionResult> addCourses(
    List<Map<String, Object?>> items,
    List<String> reuseTodoIds, {
    List<String> reviewResourceIds = const [],
    String? requestId,
  }) async {
    return CourseAdditionResult.fromJson(
      await _api.postJson(
        '/api/v1/todos/course-additions',
        data: {
          'items': items,
          'reuseTodoIds': reuseTodoIds,
          'requestId': ?requestId,
          if (reviewResourceIds.isNotEmpty)
            'reviewResourceIds': reviewResourceIds,
        },
      ),
    );
  }

  /// 创建课程 Todo；目标进度用千分比表示。
  Map<String, Object?> courseTodoPayload({
    required String resourceId,
    required int targetProgressPermille,
    DateTime? localDate,
    String? title,
    String? note,
    bool requireEvidence = false,
  }) {
    return {
      'todoType': 'COURSE',
      'resourceId': resourceId,
      'targetProgressPermille': targetProgressPermille,
      if (localDate != null) 'localDate': _formatDate(localDate),
      'title': ?title,
      'note': ?note,
      'requireEvidence': requireEvidence,
    };
  }

  Map<String, Object?> focusTodoPayload({
    required String title,
    required int plannedSeconds,
    DateTime? localDate,
    String? note,
    bool requireEvidence = false,
  }) {
    return {
      'todoType': 'FOCUS',
      'title': title,
      'plannedSeconds': plannedSeconds,
      if (localDate != null) 'localDate': _formatDate(localDate),
      'note': ?note,
      'requireEvidence': requireEvidence,
    };
  }

  Map<String, Object?> taskTodoPayload({
    required String title,
    DateTime? localDate,
    String? note,
    bool requireEvidence = false,
  }) {
    return {
      'todoType': 'TASK',
      'title': title,
      if (localDate != null) 'localDate': _formatDate(localDate),
      'note': ?note,
      'requireEvidence': requireEvidence,
    };
  }

  Future<void> patchTodo(
    String todoId, {
    String? title,
    String? note,
    int? targetProgressPermille,
    int? plannedSeconds,
    bool? requireEvidence,
    List<NoteTag>? noteTags,
  }) async {
    await _api.patchJson(
      '/api/v1/todos/$todoId',
      data: {
        'title': ?title,
        'note': ?note,
        'targetProgressPermille': ?targetProgressPermille,
        'plannedSeconds': ?plannedSeconds,
        'requireEvidence': ?requireEvidence,
        if (noteTags != null)
          'noteTags': noteTags.map((tag) => tag.wire).toList(growable: false),
      },
    );
  }

  Future<void> reorder(DateTime localDate, List<String> orderedIds) async {
    await _api.postEmpty(
      '/api/v1/todos/reorder',
      data: {'localDate': _formatDate(localDate), 'orderedIds': orderedIds},
    );
  }

  /// 上报一次进度；服务端返回裁决结果。
  Future<ProgressResult> reportProgress(
    String todoId, {
    required int clientSeq,
    required DateTime occurredAt,
    String eventType = 'PROGRESS',
    int? positionMs,
    int? positionPage,
    int deltaWatchedMs = 0,
    int deltaFocusedMs = 0,
    bool foreground = true,
  }) async {
    return ProgressResult.fromJson(
      await _api.postJson(
        '/api/v1/todos/$todoId/progress',
        data: {
          'clientSeq': clientSeq,
          'occurredAt': occurredAt.toUtc().toIso8601String(),
          'eventType': eventType,
          'positionMs': ?positionMs,
          'positionPage': ?positionPage,
          'deltaWatchedMs': deltaWatchedMs,
          'deltaFocusedMs': deltaFocusedMs,
          'appState': foreground ? 'FOREGROUND' : 'BACKGROUND',
        },
      ),
    );
  }

  Future<void> completeTodo(
    String todoId, {
    String? note,
    List<NoteTag> noteTags = const [],
    bool backfill = false,
  }) async {
    await _api.postEmpty(
      '/api/v1/todos/$todoId/complete',
      data: {
        'note': ?note,
        'noteTags': noteTags.map((tag) => tag.wire).toList(growable: false),
        'backfill': backfill,
      },
    );
  }

  Future<void> annotateTodo(
    String todoId, {
    String? note,
    List<NoteTag> noteTags = const [],
  }) async {
    await _api.postEmpty(
      '/api/v1/todos/$todoId/annotate',
      data: {
        'note': ?note,
        'noteTags': noteTags.map((tag) => tag.wire).toList(growable: false),
      },
    );
  }

  /// 操作后使用服务端快照校准计时；请求重试时复用标识，避免重复记账。
  Future<TodoItem> focusAction(
    String todoId,
    String action, {
    String? requestId,
  }) async {
    final suffix = requestId == null
        ? ''
        : '?requestId=${Uri.encodeQueryComponent(requestId)}';
    return TodoItem.fromJson(
      await _api.postJson('/api/v1/todos/$todoId/focus/$action$suffix'),
    );
  }

  Future<void> deferTodo(String todoId, DateTime targetDate) async {
    await _api.postEmpty(
      '/api/v1/todos/$todoId/defer',
      data: {'targetDate': _formatDate(targetDate)},
    );
  }

  Future<void> deferAll(List<String> todoIds, DateTime targetDate) async {
    await _api.postEmpty(
      '/api/v1/todos/batch-defer',
      data: {'todoIds': todoIds, 'targetDate': _formatDate(targetDate)},
    );
  }

  /// 删除单条 Todo；原因标签与说明都必填。
  Future<void> deleteTodo(
    String todoId, {
    required DeletionReasonTag reasonTag,
    required String reasonText,
  }) async {
    await _api.deleteWithBody(
      '/api/v1/todos/$todoId',
      data: {'reasonTag': reasonTag.wire, 'reasonText': reasonText},
    );
  }

  Future<void> deleteTodos(
    List<String> todoIds, {
    required DeletionReasonTag reasonTag,
    required String reasonText,
  }) async {
    await _api.postEmpty(
      '/api/v1/todos/batch-delete',
      data: {
        'todoIds': todoIds,
        'reasonTag': reasonTag.wire,
        'reasonText': reasonText,
      },
    );
  }

  // ---------------- Todo 附件（完成凭证） ----------------

  Future<List<TodoAttachment>> loadAttachments(String todoId) async {
    final list = await _api.getJsonList('/api/v1/todos/$todoId/attachments');
    return list.map(TodoAttachment.fromJson).toList(growable: false);
  }

  /// 上传一个附件，返回服务端新建的附件视图。
  ///
  /// 这是「完成凭证必填」（`requireEvidence`）唯一的解除入口：没有任何附件时
  /// 完成接口会返回 `TODO_EVIDENCE_REQUIRED`。大小、类型与数量三道限制全部由服务端
  /// 裁决，分别对应 `ATTACHMENT_TOO_LARGE`、`ATTACHMENT_TYPE_UNSUPPORTED` 与
  /// `ATTACHMENT_LIMIT_REACHED`，客户端不重复判定。
  Future<TodoAttachment> uploadAttachment(
    String todoId, {
    required String filename,
    required String contentType,
    required List<int> bytes,
  }) async {
    return TodoAttachment.fromJson(
      await _api.postFile(
        '/api/v1/todos/$todoId/attachments',
        fieldName: 'file',
        filename: filename,
        contentType: contentType,
        bytes: bytes,
      ),
    );
  }

  Future<void> deleteAttachment(String todoId, String attachmentId) async {
    await _api.deleteEmpty('/api/v1/todos/$todoId/attachments/$attachmentId');
  }

  /// 读取附件字节，用于缩略图与预览。
  ///
  /// 下载端点要求 Bearer Token，页面不能直接用 `Image.network`，
  /// 只能先经这里拿到字节再交给 `Image.memory`。
  Future<Uint8List> loadAttachmentBytes(String todoId, String attachmentId) {
    return _api.getBytes(
      '/api/v1/todos/$todoId/attachments/$attachmentId/content',
    );
  }

  // ---------------- 课程库 ----------------

  Future<CatalogFacets> loadFacets() async {
    return CatalogFacets.fromJson(await _api.getJson('/api/v1/catalog/facets'));
  }

  Future<List<CourseSummary>> loadCourses({
    String? genre,
    String? tag,
    String? person,
    int? year,
    String? query,
  }) async {
    final params = <String>[
      if (genre != null && genre.isNotEmpty)
        'genre=${Uri.encodeQueryComponent(genre)}',
      if (tag != null && tag.isNotEmpty) 'tag=${Uri.encodeQueryComponent(tag)}',
      if (person != null && person.isNotEmpty)
        'person=${Uri.encodeQueryComponent(person)}',
      if (year != null) 'year=$year',
      if (query != null && query.isNotEmpty)
        'q=${Uri.encodeQueryComponent(query)}',
    ];
    final suffix = params.isEmpty ? '' : '?${params.join('&')}';
    final list = await _api.getJsonList('/api/v1/catalog/courses$suffix');
    return list.map(CourseSummary.fromJson).toList(growable: false);
  }

  /// 经认证的服务端封面代理，不向客户端暴露 Emby 地址和凭据。
  Future<Uint8List> loadCourseCover(String courseId) => _api.getBytes(
    '/api/v1/catalog/courses/${Uri.encodeComponent(courseId)}/cover',
  );

  Future<CourseDetail> loadCourse(String courseId) async {
    return CourseDetail.fromJson(
      await _api.getJson('/api/v1/catalog/courses/$courseId'),
    );
  }

  // ---------------- 统计 ----------------

  Future<StatsView> loadStats({String range = 'DAY', DateTime? date}) async {
    final query = date == null ? '' : '&date=${_formatDate(date)}';
    return StatsView.fromJson(
      await _api.getJson('/api/v1/stats?range=$range$query'),
    );
  }

  // ---------------- 心跳与催办 ----------------

  Future<HeartbeatResult> heartbeat({
    required bool foreground,
    required String clientVersion,
    int queuedEvents = 0,
    AppActivity? activity,
  }) async {
    return HeartbeatResult.fromJson(
      await _api.postJson(
        '/api/v1/heartbeat',
        data: {
          'appState': foreground ? 'FOREGROUND' : 'BACKGROUND',
          'clientVersion': clientVersion,
          'queuedEvents': queuedEvents,
          'currentPage': activity?.page,
          'activityState': activity?.state,
          'activityTodoId': activity?.todoId,
        },
      ),
    );
  }

  /// ready 与 nag 帧均只提示补查，业务数据仍通过待回应接口读取。
  Stream<String> watchNagEvents() => _api.eventStream('/api/v1/nags/events');

  Future<PendingNag?> loadPendingNag() async {
    final json = await _api.getOptionalJson('/api/v1/nags/pending');
    return json == null ? null : PendingNag.fromJson(json);
  }

  Future<void> respondNag(
    String nagId, {
    required String reasonTag,
    required String reasonText,
  }) async {
    await _api.postEmpty(
      '/api/v1/nags/$nagId/respond',
      data: {'reasonTag': reasonTag, 'reasonText': reasonText},
    );
  }

  // ---------------- 督学端 ----------------

  Future<List<LearnerOverview>> loadLearners() async {
    final list = await _api.getJsonList('/api/v1/supervisor/learners');
    return list.map(LearnerOverview.fromJson).toList(growable: false);
  }

  Future<LearnerDetail> loadLearner(
    String learnerId, {
    String range = 'DAY',
    DateTime? date,
  }) async {
    final query = date == null ? '' : '&date=${_formatDate(date)}';
    return LearnerDetail.fromJson(
      await _api.getJson(
        '/api/v1/supervisor/learners/$learnerId?range=$range$query',
      ),
    );
  }

  Future<List<SupervisorFeedItem>> loadFeed({int limit = 50}) async {
    final list = await _api.getJsonList('/api/v1/supervisor/feed?limit=$limit');
    return list.map(SupervisorFeedItem.fromJson).toList(growable: false);
  }

  Future<List<LearnerReport>> loadReports({String range = 'WEEK'}) async {
    final list = await _api.getJsonList(
      '/api/v1/supervisor/report?range=$range',
    );
    return list.map(LearnerReport.fromJson).toList(growable: false);
  }

  Future<void> nagLearner(
    String learnerId, {
    String? message,
    String? title,
    String channel = 'AUTO',
    bool requireReason = true,
  }) async {
    await _api.postEmpty(
      '/api/v1/supervisor/learners/$learnerId/nag',
      data: {
        if (message != null && message.isNotEmpty) 'message': message,
        if (title != null && title.isNotEmpty) 'title': title,
        'channel': channel,
        'requireReason': requireReason,
      },
    );
  }

  static String _formatDate(DateTime date) {
    return '${date.year.toString().padLeft(4, '0')}-'
        '${date.month.toString().padLeft(2, '0')}-'
        '${date.day.toString().padLeft(2, '0')}';
  }
}
