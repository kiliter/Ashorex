import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 服务端返回的作战单项目；不可修改标志是服务端状态机的直接投影。
final class PlanItemData {
  const PlanItemData({
    required this.id,
    required this.itemType,
    required this.title,
    required this.mediaItemId,
    required this.mockExamPresetId,
    required this.mockExamName,
    required this.plannedSeconds,
    required this.completedSeconds,
    required this.status,
    required this.sortOrder,
    required this.immutable,
    this.courseId,
    this.courseName,
    this.quizRequired = false,
    this.debtId,
  });

  final String id;
  final String itemType;
  final String title;
  final String? mediaItemId;
  final String? mockExamPresetId;
  final String? mockExamName;
  final int plannedSeconds;
  final int completedSeconds;
  final String status;
  final int sortOrder;
  final bool immutable;
  final String? courseId;
  final String? courseName;
  final bool quizRequired;
  final String? debtId;

  factory PlanItemData.fromJson(Map<String, dynamic> json) => PlanItemData(
    id: json['id'] as String,
    itemType: json['itemType'] as String,
    title: json['title'] as String,
    mediaItemId: json['mediaItemId'] as String?,
    mockExamPresetId: json['mockExamPresetId'] as String?,
    mockExamName: json['mockExamName'] as String?,
    plannedSeconds: (json['plannedSeconds'] as num).toInt(),
    completedSeconds: (json['completedSeconds'] as num).toInt(),
    status: json['status'] as String,
    sortOrder: (json['sortOrder'] as num?)?.toInt() ?? 0,
    immutable: json['immutable'] as bool? ?? false,
    courseId: json['courseId'] as String?,
    courseName: json['courseName'] as String?,
    quizRequired: json['quizRequired'] as bool? ?? false,
    debtId: json['debtId'] as String?,
  );
}

/// 今日作战单使用版本号和完整快照保存，避免多次局部写入产生半成品。
final class DailyPlanData {
  const DailyPlanData({
    required this.id,
    required this.date,
    required this.status,
    required this.version,
    required this.items,
  });

  final String? id;
  final DateTime date;
  final String status;
  final int version;
  final List<PlanItemData> items;

  factory DailyPlanData.fromJson(Map<String, dynamic> json) => DailyPlanData(
    id: json['id'] as String?,
    date: DateTime.parse(json['date'] as String),
    status: json['status'] as String,
    version: (json['version'] as num?)?.toInt() ?? 0,
    items: (json['items'] as List<dynamic>? ?? const [])
        .map(
          (item) =>
              PlanItemData.fromJson(Map<String, dynamic>.from(item as Map)),
        )
        .toList(),
  );
}

/// 编排区内的客户端草稿，保存前不代表任何服务端业务状态。
final class BattleOrderDraft {
  const BattleOrderDraft({
    required this.existingItemId,
    required this.itemType,
    required this.title,
    required this.mediaItemId,
    required this.mockExamPresetId,
    required this.plannedSeconds,
    required this.immutable,
    required this.catalogOrder,
    this.courseId,
    this.courseName,
  });

  final String? existingItemId;
  final String itemType;
  final String title;
  final String? mediaItemId;
  final String? mockExamPresetId;
  final int plannedSeconds;
  final bool immutable;

  /// 课时在服务端课程目录中的固有顺序；模拟考试为 null，并统一排在课时之后。
  final int? catalogOrder;
  final String? courseId;
  final String? courseName;

  factory BattleOrderDraft.fromSaved(PlanItemData item) => BattleOrderDraft(
    existingItemId: item.id,
    itemType: item.itemType,
    title: item.title,
    mediaItemId: item.mediaItemId,
    mockExamPresetId: item.mockExamPresetId,
    plannedSeconds: item.plannedSeconds,
    immutable: item.immutable,
    catalogOrder: item.mediaItemId == null ? null : item.sortOrder,
    courseId: item.courseId,
    courseName: item.courseName,
  );

  Map<String, dynamic> toJson(int sortOrder) => {
    'existingItemId': existingItemId,
    'itemType': itemType,
    'mediaItemId': mediaItemId,
    'mockExamPresetId': mockExamPresetId,
    'sortOrder': sortOrder,
  };
}

/// 学习欠债是服务端日终结算结果，客户端只能读取，不能直接核销或豁免。
final class LearningDebtData {
  const LearningDebtData({
    required this.id,
    required this.debtType,
    required this.title,
    required this.remainingSeconds,
    required this.status,
    this.mediaItemId,
    this.openedOn,
  });

  final String id;
  final String debtType;
  final String title;
  final int remainingSeconds;
  final String status;
  final String? mediaItemId;
  final DateTime? openedOn;

  factory LearningDebtData.fromJson(Map<String, dynamic> json) {
    final opened = json['openedOn'] as String?;
    return LearningDebtData(
      id: json['id'] as String,
      debtType: json['debtType'] as String,
      title: json['title'] as String,
      remainingSeconds: (json['remainingSeconds'] as num).toInt(),
      status: json['status'] as String,
      mediaItemId: json['mediaItemId'] as String?,
      openedOn: opened == null ? null : DateTime.tryParse(opened),
    );
  }
}

/// 学习日历用的单日摘要，不携带项目明细。
final class PlanCalendarDay {
  const PlanCalendarDay({
    required this.date,
    required this.status,
    required this.completed,
    required this.hasDebt,
    required this.itemCount,
    required this.completedItemCount,
    this.plannedSeconds = 0,
  });

  final DateTime date;
  final String status;
  final bool completed;
  final bool hasDebt;
  final int itemCount;
  final int completedItemCount;
  final int plannedSeconds;

  factory PlanCalendarDay.fromJson(Map<String, dynamic> json) =>
      PlanCalendarDay(
        date: DateTime.parse(json['date'] as String),
        status: json['status'] as String,
        completed: json['completed'] as bool? ?? false,
        hasDebt: json['hasDebt'] as bool? ?? false,
        itemCount: (json['itemCount'] as num?)?.toInt() ?? 0,
        completedItemCount: (json['completedItemCount'] as num?)?.toInt() ?? 0,
        plannedSeconds: (json['plannedSeconds'] as num?)?.toInt() ?? 0,
      );
}

abstract interface class PlanRepository {
  Future<DailyPlanData> loadToday();

  Future<DailyPlanData> load(DateTime date);

  Future<List<PlanCalendarDay>> loadCalendar({
    required DateTime from,
    required DateTime to,
  });

  Future<DailyPlanData> saveToday({
    required int expectedVersion,
    required List<BattleOrderDraft> items,
  });

  Future<List<LearningDebtData>> loadDebts();
}

/// 作战单 API 只有读取与整单保存两个入口。
final class RemotePlanRepository implements PlanRepository {
  RemotePlanRepository(this._api);

  final ApiClient _api;

  String _datePath(DateTime date) {
    return '${date.year.toString().padLeft(4, '0')}-'
        '${date.month.toString().padLeft(2, '0')}-'
        '${date.day.toString().padLeft(2, '0')}';
  }

  String get _today => _datePath(DateTime.now());

  @override
  Future<DailyPlanData> loadToday() async => load(DateTime.now());

  @override
  Future<DailyPlanData> load(DateTime date) async => DailyPlanData.fromJson(
    await _api.getJson('/api/v1/plans/${_datePath(date)}'),
  );

  @override
  Future<List<PlanCalendarDay>> loadCalendar({
    required DateTime from,
    required DateTime to,
  }) async {
    final json = await _api.getJson(
      '/api/v1/plans?from=${_datePath(from)}&to=${_datePath(to)}',
    );
    return (json['days'] as List<dynamic>? ?? const [])
        .map(
          (item) =>
              PlanCalendarDay.fromJson(Map<String, dynamic>.from(item as Map)),
        )
        .toList();
  }

  @override
  Future<DailyPlanData> saveToday({
    required int expectedVersion,
    required List<BattleOrderDraft> items,
  }) async => DailyPlanData.fromJson(
    await _api.putJson(
      '/api/v1/plans/$_today',
      data: {
        'expectedVersion': expectedVersion,
        'items': items.indexed
            .map((entry) => entry.$2.toJson(entry.$1))
            .toList(),
      },
    ),
  );

  @override
  Future<List<LearningDebtData>> loadDebts() async => (await _api.getJsonList(
    '/api/v1/debts',
  )).map(LearningDebtData.fromJson).toList();
}

final planRepositoryProvider = Provider<PlanRepository>((ref) {
  throw StateError('PlanRepository 尚未注入');
});
