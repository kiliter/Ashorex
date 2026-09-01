import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 日报快照，数值全部由服务端可信学习数据聚合。
final class DailyReportData {
  const DailyReportData({
    required this.date,
    required this.planStatus,
    required this.plannedSeconds,
    required this.videoStudySeconds,
    required this.focusSeconds,
    required this.completedTasks,
    required this.totalTasks,
    required this.completionRate,
    required this.videoCompletedCount,
    required this.answerCount,
    required this.correctAnswerCount,
    required this.answerAccuracy,
    required this.aliveCheckFailureCount,
    required this.abandoned,
    required this.newDebtSeconds,
    required this.repaidDebtSeconds,
    required this.openDebtSeconds,
    required this.judgmentText,
    required this.generatedAt,
    this.dayOutcome = 'NONE',
    this.mockExamCompletedCount = 0,
    this.mockExamAwaitingUploadCount = 0,
  });

  final DateTime date;
  final String planStatus;
  final int plannedSeconds;
  final int videoStudySeconds;
  final int focusSeconds;
  final int completedTasks;
  final int totalTasks;
  final int completionRate;
  final int videoCompletedCount;
  final int answerCount;
  final int correctAnswerCount;
  final int answerAccuracy;
  final int aliveCheckFailureCount;
  final bool abandoned;
  final int newDebtSeconds;
  final int repaidDebtSeconds;
  final int openDebtSeconds;
  final String judgmentText;
  final DateTime generatedAt;
  final String dayOutcome;
  final int mockExamCompletedCount;
  final int mockExamAwaitingUploadCount;

  factory DailyReportData.fromJson(Map<String, dynamic> json) =>
      DailyReportData(
        date: DateTime.parse(json['date'] as String),
        planStatus: json['planStatus'] as String,
        plannedSeconds: (json['plannedSeconds'] as num).toInt(),
        videoStudySeconds: (json['videoStudySeconds'] as num).toInt(),
        focusSeconds: (json['focusSeconds'] as num).toInt(),
        completedTasks: (json['completedTasks'] as num).toInt(),
        totalTasks: (json['totalTasks'] as num).toInt(),
        completionRate: (json['completionRate'] as num).toInt(),
        videoCompletedCount: (json['videoCompletedCount'] as num).toInt(),
        answerCount: (json['answerCount'] as num).toInt(),
        correctAnswerCount: (json['correctAnswerCount'] as num).toInt(),
        answerAccuracy: (json['answerAccuracy'] as num).toInt(),
        aliveCheckFailureCount: (json['aliveCheckFailureCount'] as num).toInt(),
        abandoned: json['abandoned'] as bool,
        newDebtSeconds: (json['newDebtSeconds'] as num).toInt(),
        repaidDebtSeconds: (json['repaidDebtSeconds'] as num).toInt(),
        openDebtSeconds: (json['openDebtSeconds'] as num).toInt(),
        judgmentText: json['judgmentText'] as String,
        generatedAt: DateTime.parse(json['generatedAt'] as String),
        dayOutcome: json['dayOutcome'] as String? ?? 'NONE',
        mockExamCompletedCount:
            (json['mockExamCompletedCount'] as num?)?.toInt() ?? 0,
        mockExamAwaitingUploadCount:
            (json['mockExamAwaitingUploadCount'] as num?)?.toInt() ?? 0,
      );
}

/// 周报中的复习审计只统计课时和次数，不包含复习时长。
final class ReviewedLessonData {
  const ReviewedLessonData({
    required this.mediaItemId,
    required this.lessonTitle,
    required this.reviewCount,
  });

  final String mediaItemId;
  final String lessonTitle;
  final int reviewCount;

  factory ReviewedLessonData.fromJson(Map<String, dynamic> json) =>
      ReviewedLessonData(
        mediaItemId: json['mediaItemId'] as String,
        lessonTitle: json['lessonTitle'] as String,
        reviewCount: (json['reviewCount'] as num).toInt(),
      );
}

final class WeeklyDayTrendData {
  const WeeklyDayTrendData({
    required this.date,
    required this.effectiveStudySeconds,
    required this.completionRate,
    required this.newDebtSeconds,
    required this.repaidDebtSeconds,
  });

  final DateTime date;
  final int effectiveStudySeconds;
  final int completionRate;
  final int newDebtSeconds;
  final int repaidDebtSeconds;

  factory WeeklyDayTrendData.fromJson(Map<String, dynamic> json) =>
      WeeklyDayTrendData(
        date: DateTime.parse(json['date'] as String),
        effectiveStudySeconds: (json['effectiveStudySeconds'] as num).toInt(),
        completionRate: (json['completionRate'] as num).toInt(),
        newDebtSeconds: (json['newDebtSeconds'] as num).toInt(),
        repaidDebtSeconds: (json['repaidDebtSeconds'] as num).toInt(),
      );
}

/// 周报包含本周汇总、逐日趋势和与上周的绝对变化。
final class WeeklyReportData {
  const WeeklyReportData({
    required this.weekStart,
    required this.days,
    required this.totalEffectiveStudySeconds,
    required this.videoStudySeconds,
    required this.focusSeconds,
    required this.videoCompletedCount,
    required this.answerCount,
    required this.answerAccuracy,
    required this.planCompletionRate,
    required this.newDebtSeconds,
    required this.repaidDebtSeconds,
    required this.abandonmentCount,
    required this.aliveCheckFailureCount,
    required this.previousWeekEffectiveStudySeconds,
    required this.effectiveStudySecondsChange,
    required this.previousWeekPlanCompletionRate,
    required this.planCompletionRateChange,
    this.slackedDayCount = 0,
    this.reviewedLessons = const [],
  });

  final DateTime weekStart;
  final List<WeeklyDayTrendData> days;
  final int totalEffectiveStudySeconds;
  final int videoStudySeconds;
  final int focusSeconds;
  final int videoCompletedCount;
  final int answerCount;
  final int answerAccuracy;
  final int planCompletionRate;
  final int newDebtSeconds;
  final int repaidDebtSeconds;
  final int abandonmentCount;
  final int aliveCheckFailureCount;
  final int previousWeekEffectiveStudySeconds;
  final int effectiveStudySecondsChange;
  final int previousWeekPlanCompletionRate;
  final int planCompletionRateChange;
  final int slackedDayCount;
  final List<ReviewedLessonData> reviewedLessons;

  factory WeeklyReportData.fromJson(Map<String, dynamic> json) =>
      WeeklyReportData(
        weekStart: DateTime.parse(json['weekStart'] as String),
        days: (json['days'] as List)
            .map(
              (item) => WeeklyDayTrendData.fromJson(
                Map<String, dynamic>.from(item as Map),
              ),
            )
            .toList(),
        totalEffectiveStudySeconds: (json['totalEffectiveStudySeconds'] as num)
            .toInt(),
        videoStudySeconds: (json['videoStudySeconds'] as num).toInt(),
        focusSeconds: (json['focusSeconds'] as num).toInt(),
        videoCompletedCount: (json['videoCompletedCount'] as num).toInt(),
        answerCount: (json['answerCount'] as num).toInt(),
        answerAccuracy: (json['answerAccuracy'] as num).toInt(),
        planCompletionRate: (json['planCompletionRate'] as num).toInt(),
        newDebtSeconds: (json['newDebtSeconds'] as num).toInt(),
        repaidDebtSeconds: (json['repaidDebtSeconds'] as num).toInt(),
        abandonmentCount: (json['abandonmentCount'] as num).toInt(),
        aliveCheckFailureCount: (json['aliveCheckFailureCount'] as num).toInt(),
        previousWeekEffectiveStudySeconds:
            (json['previousWeekEffectiveStudySeconds'] as num).toInt(),
        effectiveStudySecondsChange:
            (json['effectiveStudySecondsChange'] as num).toInt(),
        previousWeekPlanCompletionRate:
            (json['previousWeekPlanCompletionRate'] as num).toInt(),
        planCompletionRateChange: (json['planCompletionRateChange'] as num)
            .toInt(),
        slackedDayCount: (json['slackedDayCount'] as num?)?.toInt() ?? 0,
        reviewedLessons: (json['reviewedLessons'] as List<dynamic>? ?? const [])
            .map(
              (item) => ReviewedLessonData.fromJson(
                Map<String, dynamic>.from(item as Map),
              ),
            )
            .toList(),
      );
}

abstract interface class ReportRepository {
  Future<DailyReportData> loadDaily(DateTime date);

  Future<WeeklyReportData> loadWeekly(DateTime weekStart);
}

final class RemoteReportRepository implements ReportRepository {
  RemoteReportRepository(this._api);

  final ApiClient _api;

  @override
  Future<DailyReportData> loadDaily(DateTime date) async =>
      DailyReportData.fromJson(
        await _api.getJson('/api/v1/reports/daily?date=${_date(date)}'),
      );

  @override
  Future<WeeklyReportData> loadWeekly(DateTime weekStart) async =>
      WeeklyReportData.fromJson(
        await _api.getJson(
          '/api/v1/reports/weekly?weekStart=${_date(weekStart)}',
        ),
      );
}

String _date(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}-'
    '${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')}';

final reportRepositoryProvider = Provider<ReportRepository>((ref) {
  throw StateError('ReportRepository 尚未注入');
});
