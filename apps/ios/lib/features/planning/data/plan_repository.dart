import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 每日计划中的服务端任务快照。
final class PlanItemData {
  const PlanItemData({
    required this.id,
    required this.itemType,
    required this.title,
    required this.plannedSeconds,
    required this.completedSeconds,
    required this.status,
  });

  final String id;
  final String itemType;
  final String title;
  final int plannedSeconds;
  final int completedSeconds;
  final String status;

  factory PlanItemData.fromJson(Map<String, dynamic> json) => PlanItemData(
    id: json['id'] as String,
    itemType: json['itemType'] as String,
    title: json['title'] as String,
    plannedSeconds: (json['plannedSeconds'] as num).toInt(),
    completedSeconds: (json['completedSeconds'] as num).toInt(),
    status: json['status'] as String,
  );
}

final class DailyPlanData {
  const DailyPlanData({
    required this.id,
    required this.date,
    required this.status,
    required this.items,
  });

  final String id;
  final DateTime date;
  final String status;
  final List<PlanItemData> items;

  factory DailyPlanData.fromJson(Map<String, dynamic> json) => DailyPlanData(
    id: json['id'] as String,
    date: DateTime.parse(json['date'] as String),
    status: json['status'] as String,
    items: (json['items'] as List)
        .map(
          (item) =>
              PlanItemData.fromJson(Map<String, dynamic>.from(item as Map)),
        )
        .toList(),
  );
}

final class DebtPreviewData {
  const DebtPreviewData({
    required this.type,
    required this.title,
    required this.seconds,
  });

  final String type;
  final String title;
  final int seconds;

  factory DebtPreviewData.fromJson(Map<String, dynamic> json) =>
      DebtPreviewData(
        type: json['type'] as String,
        title: json['title'] as String,
        seconds: (json['seconds'] as num).toInt(),
      );
}

final class AbandonPreviewData {
  const AbandonPreviewData({
    required this.debtCount,
    required this.addedDebtSeconds,
    required this.debts,
  });

  final int debtCount;
  final int addedDebtSeconds;
  final List<DebtPreviewData> debts;

  factory AbandonPreviewData.fromJson(Map<String, dynamic> json) =>
      AbandonPreviewData(
        debtCount: (json['debtCount'] as num).toInt(),
        addedDebtSeconds: (json['addedDebtSeconds'] as num).toInt(),
        debts: (json['debts'] as List)
            .map(
              (item) => DebtPreviewData.fromJson(
                Map<String, dynamic>.from(item as Map),
              ),
            )
            .toList(),
      );
}

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
  Future<DailyPlanData> addVideo(String lessonId);
  Future<DailyPlanData> addFocus(String title, int seconds);
  Future<DailyPlanData> lockToday();
  Future<AbandonPreviewData> previewAbandon();
  Future<DailyPlanData> abandon(String reasonCode, String reasonText);
  Future<List<LearningDebtData>> loadDebts();
  Future<DailyPlanData> addDebtItems(List<String> debtIds);
}

/// 计划日期仅用于请求路径；状态和完成量始终以服务端返回为准。
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
  Future<DailyPlanData> addVideo(String lessonId) async =>
      DailyPlanData.fromJson(
        await _api.postJson(
          '/api/v1/plans/$_today/items',
          data: {
            'itemType': 'VIDEO',
            'title': '视频学习',
            'mediaItemId': lessonId,
            'plannedSeconds': 1,
            'sortOrder': 0,
          },
        ),
      );

  @override
  Future<DailyPlanData> addFocus(String title, int seconds) async =>
      DailyPlanData.fromJson(
        await _api.postJson(
          '/api/v1/plans/$_today/items',
          data: {
            'itemType': 'FOCUS',
            'title': title,
            'plannedSeconds': seconds,
            'sortOrder': 100,
          },
        ),
      );

  @override
  Future<DailyPlanData> lockToday() async =>
      DailyPlanData.fromJson(await _api.postJson('/api/v1/plans/$_today/lock'));

  @override
  Future<AbandonPreviewData> previewAbandon() async =>
      AbandonPreviewData.fromJson(
        await _api.getJson('/api/v1/plans/$_today/abandon-preview'),
      );

  @override
  Future<DailyPlanData> abandon(String reasonCode, String reasonText) async =>
      DailyPlanData.fromJson(
        await _api.postJson(
          '/api/v1/plans/$_today/abandon',
          data: {'reasonCode': reasonCode, 'reasonText': reasonText},
        ),
      );

  @override
  Future<List<LearningDebtData>> loadDebts() async =>
      (await _api.getJsonList('/api/v1/debts'))
          .map(LearningDebtData.fromJson)
          .toList();

  @override
  Future<DailyPlanData> addDebtItems(List<String> debtIds) async =>
      DailyPlanData.fromJson(
        await _api.postJson(
          '/api/v1/plans/$_today/debt-items',
          data: {'debtIds': debtIds},
        ),
      );
}

final planRepositoryProvider = Provider<PlanRepository>((ref) {
  throw StateError('PlanRepository 尚未注入');
});
