import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';
import 'package:shangan_ios/features/dashboard/presentation/home_page.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';

void main() {
  testWidgets('首页展示考试倒计时和有风险的进度压力', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          dashboardRepositoryProvider.overrideWithValue(_DashboardRepository()),
        ],
        child: const MaterialApp(home: HomePage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('2026 国考'), findsOneWidget);
    expect(find.text('距离考试 63 天'), findsOneWidget);
    expect(find.text('进度有风险'), findsOneWidget);
    expect(find.textContaining('剩余 81 课时'), findsOneWidget);
    expect(find.text('今日追赶中'), findsOneWidget);
    expect(find.text('今日作战单'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('学习欠债'), 240);
    expect(find.text('学习欠债'), findsOneWidget);
  });
}

final class _DashboardRepository implements DashboardRepository {
  @override
  Future<DashboardData> load() async => DashboardData(
    exam: ExamGoal(
      id: 'goal-1',
      name: '2026 国考',
      examDate: DateTime(2026, 11),
      targetCompletionDate: DateTime(2026, 10, 18),
      reviewBufferDays: 14,
      timezone: 'Asia/Shanghai',
      courseIds: const ['course-1'],
    ),
    progressPressure: ProgressPressure(
      daysUntilExam: 63,
      daysUntilTarget: 49,
      totalLessons: 100,
      completedLessons: 19,
      remainingLessons: 81,
      requiredDailyPace: 81 / 49,
      actualDailyPace: 1,
      projectedFinishDate: DateTime(2026, 11, 19),
      riskStatus: 'AT_RISK',
    ),
    todayPlanStatus: 'NONE',
    openDebtSeconds: 0,
    studyTodaySeconds: 0,
    answerAccuracy: 0,
  );
}
