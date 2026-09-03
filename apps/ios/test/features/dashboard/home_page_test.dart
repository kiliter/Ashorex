import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';
import 'package:shangan_ios/features/dashboard/presentation/home_page.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

void main() {
  testWidgets('首页先展示考试倒计时再展示作战单任务列表', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          dashboardRepositoryProvider.overrideWithValue(_DashboardRepository()),
          planRepositoryProvider.overrideWithValue(_PlanRepository()),
        ],
        child: const MaterialApp(home: HomePage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('2026 国考'), findsWidgets);
    expect(find.text('距离考试 63 天'), findsOneWidget);
    expect(find.text('进度有风险'), findsOneWidget);
    expect(find.textContaining('剩余 81 课时'), findsOneWidget);
    expect(find.text('今日追赶中'), findsNothing);
    expect(find.text('今日作战单'), findsOneWidget);
    expect(
      tester.getTopLeft(find.text('距离考试 63 天')).dy,
      lessThan(tester.getTopLeft(find.text('今日作战单')).dy),
    );
    expect(find.text('4 项任务，含 1 项欠债'), findsOneWidget);
    expect(find.text('判断推理 12'), findsOneWidget);
    expect(find.text('言语理解 01'), findsNothing);
    expect(find.text('资料分析 07 速算'), findsNothing);
    expect(find.text('偿还 09/02 判断推理欠债'), findsNothing);
    expect(find.textContaining('进行中'), findsOneWidget);
    expect(find.textContaining('继续判断推理 12'), findsOneWidget);
  });

  testWidgets('切换考试只更新倒计时卡片，不整页重新加载', (tester) async {
    final dashboard = _DashboardRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          dashboardRepositoryProvider.overrideWithValue(dashboard),
          planRepositoryProvider.overrideWithValue(_PlanRepository()),
        ],
        child: const MaterialApp(home: HomePage()),
      ),
    );
    await tester.pumpAndSettle();
    expect(dashboard.loadCount, 1);
    expect(find.text('距离考试 63 天'), findsOneWidget);
    expect(find.text('正在核对今日学习数据'), findsNothing);

    await tester.tap(find.byKey(const Key('examTab-goal-2')));
    await tester.pump();
    expect(find.text('正在核对今日学习数据'), findsNothing);
    expect(dashboard.loadCount, 1);
    expect(find.text('距离考试 120 天'), findsOneWidget);
    expect(find.text('今日作战单'), findsOneWidget);
  });
}

final class _DashboardRepository implements DashboardRepository {
  int loadCount = 0;

  @override
  Future<DashboardData> load() async {
    loadCount += 1;
    return DashboardData(
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
      todayPlanStatus: 'ACTIVE',
      openDebtSeconds: 0,
      studyTodaySeconds: 0,
      answerAccuracy: 0,
      exams: [
        ExamOverview(
          exam: ExamGoal(
            id: 'goal-1',
            name: '2026 国考',
            examDate: DateTime(2026, 11),
            targetCompletionDate: DateTime(2026, 10, 18),
            reviewBufferDays: 14,
            timezone: 'Asia/Shanghai',
            courseIds: const ['course-1'],
          ),
          progress: ProgressPressure(
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
        ),
        ExamOverview(
          exam: ExamGoal(
            id: 'goal-2',
            name: '省考',
            examDate: DateTime(2026, 12, 15),
            targetCompletionDate: DateTime(2026, 11, 20),
            reviewBufferDays: 14,
            timezone: 'Asia/Shanghai',
            courseIds: const ['course-2'],
          ),
          progress: ProgressPressure(
            daysUntilExam: 120,
            daysUntilTarget: 90,
            totalLessons: 40,
            completedLessons: 2,
            remainingLessons: 38,
            requiredDailyPace: 0.4,
            actualDailyPace: 0.1,
            projectedFinishDate: DateTime(2026, 12, 20),
            riskStatus: 'ON_TRACK',
          ),
        ),
      ],
    );
  }
}

final class _PlanRepository implements PlanRepository {
  @override
  Future<DailyPlanData> loadToday() async => DailyPlanData(
    id: 'plan-today',
    date: DateTime(2026, 9, 3),
    status: 'ACTIVE',
    version: 1,
    items: const [
      PlanItemData(
        id: 'item-1',
        itemType: 'VIDEO',
        title: '判断推理 12',
        mediaItemId: 'lesson-1',
        mockExamPresetId: null,
        mockExamName: null,
        plannedSeconds: 2280,
        completedSeconds: 600,
        status: 'PENDING',
        sortOrder: 0,
        immutable: true,
        courseId: 'course-1',
        courseName: '判断推理',
        quizRequired: true,
      ),
      PlanItemData(
        id: 'item-2',
        itemType: 'DEBT_REPAYMENT',
        title: '偿还 09/02 判断推理欠债',
        mediaItemId: 'lesson-2',
        mockExamPresetId: null,
        mockExamName: null,
        plannedSeconds: 42,
        completedSeconds: 0,
        status: 'PENDING',
        sortOrder: 1,
        immutable: false,
        courseId: 'course-1',
        courseName: '判断推理',
        debtId: 'debt-1',
      ),
      PlanItemData(
        id: 'item-3',
        itemType: 'MOCK_EXAM',
        title: '资料分析 07 速算',
        mediaItemId: null,
        mockExamPresetId: 'preset-1',
        mockExamName: '资料分析 07 速算',
        plannedSeconds: 1500,
        completedSeconds: 0,
        status: 'PENDING',
        sortOrder: 2,
        immutable: false,
      ),
      PlanItemData(
        id: 'item-4',
        itemType: 'VIDEO',
        title: '言语理解 01',
        mediaItemId: 'lesson-4',
        mockExamPresetId: null,
        mockExamName: null,
        plannedSeconds: 1800,
        completedSeconds: 1800,
        status: 'COMPLETED',
        sortOrder: 3,
        immutable: false,
        courseId: 'course-2',
        courseName: '言语理解',
      ),
    ],
  );

  @override
  Future<DailyPlanData> load(DateTime date) async => loadToday();

  @override
  Future<List<PlanCalendarDay>> loadCalendar({
    required DateTime from,
    required DateTime to,
  }) async => const [];

  @override
  Future<DailyPlanData> saveToday({
    required int expectedVersion,
    required List<BattleOrderDraft> items,
  }) {
    throw UnimplementedError();
  }

  @override
  Future<List<LearningDebtData>> loadDebts() async => const [];
}
