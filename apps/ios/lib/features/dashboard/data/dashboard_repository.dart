import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';

/// 服务端计算的考试进度压力，客户端只负责展示。
final class ProgressPressure {
  const ProgressPressure({
    required this.daysUntilExam,
    required this.daysUntilTarget,
    required this.totalLessons,
    required this.completedLessons,
    required this.remainingLessons,
    required this.requiredDailyPace,
    required this.actualDailyPace,
    required this.projectedFinishDate,
    required this.riskStatus,
  });

  final int daysUntilExam;
  final int daysUntilTarget;
  final int totalLessons;
  final int completedLessons;
  final int remainingLessons;
  final double requiredDailyPace;
  final double actualDailyPace;
  final DateTime? projectedFinishDate;
  final String riskStatus;

  factory ProgressPressure.fromJson(Map<String, dynamic> json) {
    final projected = json['projectedFinishDate'] as String?;
    return ProgressPressure(
      daysUntilExam: (json['daysUntilExam'] as num).toInt(),
      daysUntilTarget: (json['daysUntilTarget'] as num).toInt(),
      totalLessons: (json['totalLessons'] as num).toInt(),
      completedLessons: (json['completedLessons'] as num).toInt(),
      remainingLessons: (json['remainingLessons'] as num).toInt(),
      requiredDailyPace: (json['requiredDailyPace'] as num).toDouble(),
      actualDailyPace: (json['actualDailyPace'] as num).toDouble(),
      projectedFinishDate: projected == null ? null : DateTime.parse(projected),
      riskStatus: json['riskStatus'] as String,
    );
  }
}

/// 首页一次请求所需的服务端聚合数据。
final class DashboardData {
  const DashboardData({
    required this.exam,
    required this.progressPressure,
    required this.todayPlanStatus,
    required this.openDebtSeconds,
    required this.studyTodaySeconds,
    required this.answerAccuracy,
  });

  final ExamGoal? exam;
  final ProgressPressure? progressPressure;
  final String todayPlanStatus;
  final int openDebtSeconds;
  final int studyTodaySeconds;
  final double answerAccuracy;

  factory DashboardData.fromJson(Map<String, dynamic> json) {
    final examJson = json['exam'];
    final pressureJson = json['progressPressure'];
    return DashboardData(
      exam: examJson is Map
          ? ExamGoal.fromJson(Map<String, dynamic>.from(examJson))
          : null,
      progressPressure: pressureJson is Map
          ? ProgressPressure.fromJson(Map<String, dynamic>.from(pressureJson))
          : null,
      todayPlanStatus:
          (json['todayPlan'] as Map?)?['status'] as String? ?? 'NONE',
      openDebtSeconds: (json['openDebtSeconds'] as num).toInt(),
      studyTodaySeconds: (json['studyTodaySeconds'] as num).toInt(),
      answerAccuracy: (json['answerAccuracy'] as num).toDouble(),
    );
  }
}

abstract interface class DashboardRepository {
  Future<DashboardData> load();
}

final class RemoteDashboardRepository implements DashboardRepository {
  RemoteDashboardRepository(this._api);

  final ApiClient _api;

  @override
  Future<DashboardData> load() async =>
      DashboardData.fromJson(await _api.getJson('/api/v1/dashboard'));
}

final dashboardRepositoryProvider = Provider<DashboardRepository>((ref) {
  throw StateError('DashboardRepository 尚未注入');
});
