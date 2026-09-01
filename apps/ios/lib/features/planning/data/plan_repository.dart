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

  factory BattleOrderDraft.fromSaved(PlanItemData item) => BattleOrderDraft(
    existingItemId: item.id,
    itemType: item.itemType,
    title: item.title,
    mediaItemId: item.mediaItemId,
    mockExamPresetId: item.mockExamPresetId,
    plannedSeconds: item.plannedSeconds,
    immutable: item.immutable,
    catalogOrder: item.mediaItemId == null ? null : item.sortOrder,
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
  });

  final String id;
  final String debtType;
  final String title;
  final int remainingSeconds;
  final String status;

  factory LearningDebtData.fromJson(Map<String, dynamic> json) =>
      LearningDebtData(
        id: json['id'] as String,
        debtType: json['debtType'] as String,
        title: json['title'] as String,
        remainingSeconds: (json['remainingSeconds'] as num).toInt(),
        status: json['status'] as String,
      );
}

abstract interface class PlanRepository {
  Future<DailyPlanData> loadToday();

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

  String get _today {
    final value = DateTime.now();
    return '${value.year.toString().padLeft(4, '0')}-'
        '${value.month.toString().padLeft(2, '0')}-'
        '${value.day.toString().padLeft(2, '0')}';
  }

  @override
  Future<DailyPlanData> loadToday() async =>
      DailyPlanData.fromJson(await _api.getJson('/api/v1/plans/$_today'));

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
