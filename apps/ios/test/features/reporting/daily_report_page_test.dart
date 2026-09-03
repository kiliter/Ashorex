import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/reporting/data/report_repository.dart';
import 'package:shangan_ios/features/reporting/presentation/daily_report_page.dart';

void main() {
  testWidgets('数据页以卡片展示完成率和晚间审判', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          reportRepositoryProvider.overrideWithValue(_ReportRepository()),
        ],
        child: const MaterialApp(home: DailyReportPage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('数据'), findsWidgets);
    expect(find.text('学习日报'), findsOneWidget);
    expect(find.text('计划完成率'), findsOneWidget);
    expect(find.text('晚间审判 · 规则生成'), findsOneWidget);
    expect(find.text('今天的学习尚未完成。'), findsOneWidget);
    expect(find.text('当日学习数据'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('查看本周学习周报'),
      240,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('查看本周学习周报'), findsOneWidget);
  });
}

final class _ReportRepository implements ReportRepository {
  @override
  Future<DailyReportData> loadDaily(DateTime date) async => DailyReportData(
    date: date,
    planStatus: 'LOCKED',
    plannedSeconds: 1500,
    videoStudySeconds: 600,
    focusSeconds: 0,
    completedTasks: 0,
    totalTasks: 1,
    completionRate: 40,
    videoCompletedCount: 0,
    answerCount: 0,
    correctAnswerCount: 0,
    answerAccuracy: 0,
    aliveCheckFailureCount: 0,
    abandoned: false,
    newDebtSeconds: 0,
    repaidDebtSeconds: 0,
    openDebtSeconds: 0,
    judgmentText: '今天的学习尚未完成。',
    generatedAt: DateTime.utc(2026, 8, 30),
  );

  @override
  Future<WeeklyReportData> loadWeekly(DateTime weekStart) {
    throw UnimplementedError();
  }
}
