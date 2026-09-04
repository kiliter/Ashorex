import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/planning/presentation/study_calendar_page.dart';

void main() {
  testWidgets('当天把欠债和今日任务放进同一张表', (tester) async {
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    expect(find.text('2026 年 9 月'), findsOneWidget);
    expect(find.text('欠'), findsNothing);
    expect(find.byKey(const Key('calendarToday')), findsOneWidget);
    expect(find.text('9 月 2 日作战单'), findsNothing);
    expect(find.text('今日作战单'), findsOneWidget);
    expect(find.text('学习欠债'), findsOneWidget);
    expect(find.textContaining('课程 1'), findsOneWidget);
    expect(find.textContaining('课程 2'), findsNothing);
    expect(find.text('判断推理强化'), findsOneWidget);
    expect(find.text('数量关系'), findsOneWidget);
    expect(
      tester.getTopLeft(find.text('判断推理强化')).dy,
      lessThan(tester.getTopLeft(find.text('数量关系')).dy),
    );
    expect(find.byKey(const Key('editBattleOrder')), findsOneWidget);
  });

  testWidgets('点击已完成的历史日只读且不出现编辑按钮', (tester) async {
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    await _expandCalendar(tester);
    await tester.tap(find.byKey(const Key('calendar-day-2026-09-01')));
    await tester.pumpAndSettle();
    expect(find.text('9 月 1 日作战单'), findsOneWidget);
    expect(find.text('今日作战单'), findsNothing);
    expect(find.byKey(const Key('editBattleOrder')), findsNothing);
    expect(find.text('资料分析'), findsOneWidget);
  });

  testWidgets('翻到下一个月只查询当月且不整页失败', (tester) async {
    final plans = _PlanRepository();
    await tester.pumpWidget(_app(plans: plans));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('calendarNextMonth')));
    await tester.pumpAndSettle();

    expect(find.text('作战日历加载失败，请稍后重试'), findsNothing);
    expect(find.text('2026 年 10 月'), findsOneWidget);
    expect(plans.lastFrom, DateTime(2026, 10));
    expect(plans.lastTo, DateTime(2026, 10, 31));
  });

  testWidgets('日历默认折叠，展开后才出现格子', (tester) async {
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('calendar-day-2026-09-03')), findsNothing);
    expect(find.text('展开月历'), findsOneWidget);
    expect(find.textContaining('点按展开月历'), findsOneWidget);
    expect(find.text('今日作战单'), findsOneWidget);

    await tester.tap(find.byKey(const Key('calendarToggle')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('calendar-day-2026-09-03')), findsOneWidget);
    expect(find.text('收起'), findsOneWidget);

    await tester.tap(find.byKey(const Key('calendarToggle')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('calendar-day-2026-09-03')), findsNothing);
    expect(find.text('展开月历'), findsOneWidget);
  });

  testWidgets('再次进入学习页会重新读取作战单', (tester) async {
    final plans = _PlanRepository();
    await tester.pumpWidget(_app(plans: plans));
    await tester.pumpAndSettle();
    final firstLoads = plans.loadCount;
    bumpStudyCalendarRefresh();
    await tester.pumpAndSettle();
    expect(plans.loadCount, greaterThan(firstLoads));
  });

  testWidgets('欠债日在日期数字旁标红点，完成日标绿点，无欠债历史日不标红', (tester) async {
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await _expandCalendar(tester);

    expect(_dotColor(tester, '2026-09-01'), ShanganColors.green);
    expect(_dotColor(tester, '2026-09-02'), ShanganColors.red);
    expect(find.byKey(const Key('calendar-dot-2026-09-03')), findsNothing);
  });

  testWidgets('没有欠债的历史作战单即使有课时也不标红点', (tester) async {
    final plans = _PlanRepository(
      calendarDays: [
        PlanCalendarDay(
          date: DateTime(2026, 9, 1),
          status: 'DRAFT',
          completed: false,
          hasDebt: false,
          itemCount: 2,
          completedItemCount: 0,
        ),
      ],
    );
    await tester.pumpWidget(_app(plans: plans));
    await tester.pumpAndSettle();
    await _expandCalendar(tester);

    expect(find.byKey(const Key('calendar-dot-2026-09-01')), findsNothing);
  });

  testWidgets('历史欠债日只把课程名标红，不把今日欠债清单并进去', (tester) async {
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await _expandCalendar(tester);
    await tester.tap(find.byKey(const Key('calendar-day-2026-09-02')));
    await tester.pumpAndSettle();

    expect(find.text('9 月 2 日作战单'), findsOneWidget);
    expect(find.text('学习欠债'), findsNothing);
    expect(find.text('判断推理强化'), findsNothing);
    expect(find.text('言语理解'), findsOneWidget);
    expect(find.text('行测'), findsOneWidget);
    final title = tester.widget<Text>(find.text('行测'));
    expect(title.style?.color, ShanganColors.red);
    expect(find.textContaining('欠债'), findsWidgets);
  });

  testWidgets('跨日不关闭 App 时学习页会切到新的今天', (tester) async {
    final today = ValueNotifier(DateTime(2026, 9, 3));
    await tester.pumpWidget(
      ValueListenableBuilder<DateTime>(
        valueListenable: today,
        builder: (_, value, _) => _app(today: value),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('今日作战单'), findsOneWidget);

    today.value = DateTime(2026, 9, 4);
    await tester.pumpAndSettle();

    expect(find.text('今日作战单'), findsOneWidget);
    expect(find.text('9 月 3 日作战单'), findsNothing);
  });
}

Color? _dotColor(WidgetTester tester, String date) {
  final widget = tester.widget<Container>(
    find.byKey(Key('calendar-dot-$date')),
  );
  return (widget.decoration as BoxDecoration?)?.color;
}

Future<void> _expandCalendar(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('calendarToggle')));
  await tester.pumpAndSettle();
}

Widget _app({_PlanRepository? plans, DateTime? today}) {
  return ProviderScope(
    overrides: [
      planRepositoryProvider.overrideWithValue(plans ?? _PlanRepository()),
    ],
    child: MaterialApp(
      home: StudyCalendarPage(today: today ?? DateTime(2026, 9, 3)),
    ),
  );
}

final class _PlanRepository implements PlanRepository {
  _PlanRepository({this.calendarDays});

  DateTime? lastFrom;
  DateTime? lastTo;
  int loadCount = 0;
  final List<PlanCalendarDay>? calendarDays;

  @override
  Future<List<PlanCalendarDay>> loadCalendar({
    required DateTime from,
    required DateTime to,
  }) async {
    lastFrom = DateTime(from.year, from.month, from.day);
    lastTo = DateTime(to.year, to.month, to.day);
    loadCount += 1;
    final days =
        calendarDays ??
        [
          PlanCalendarDay(
            date: DateTime(2026, 9, 1),
            status: 'COMPLETED',
            completed: true,
            hasDebt: false,
            itemCount: 1,
            completedItemCount: 1,
          ),
          PlanCalendarDay(
            date: DateTime(2026, 9, 2),
            status: 'CLOSED_WITH_DEBT',
            completed: false,
            hasDebt: true,
            itemCount: 1,
            completedItemCount: 0,
          ),
        ];
    return days
        .where((day) => !day.date.isBefore(from) && !day.date.isAfter(to))
        .toList();
  }

  @override
  Future<DailyPlanData> loadToday() async => load(DateTime(2026, 9, 3));

  @override
  Future<DailyPlanData> load(DateTime date) async {
    loadCount += 1;
    if (date.day == 1) {
      return DailyPlanData(
        id: 'plan-1',
        date: date,
        status: 'COMPLETED',
        version: 1,
        items: const [
          PlanItemData(
            id: 'done-1',
            itemType: 'VIDEO',
            title: '资料分析',
            mediaItemId: 'lesson-0',
            mockExamPresetId: null,
            mockExamName: null,
            plannedSeconds: 600,
            completedSeconds: 600,
            status: 'COMPLETED',
            sortOrder: 0,
            immutable: true,
            courseId: 'course-1',
            courseName: '行测',
          ),
        ],
      );
    }
    if (date.day == 2) {
      return DailyPlanData(
        id: 'plan-2',
        date: date,
        status: 'CLOSED_WITH_DEBT',
        version: 1,
        items: const [
          PlanItemData(
            id: 'debt-item-1',
            itemType: 'VIDEO',
            title: '言语理解',
            mediaItemId: 'lesson-2',
            mockExamPresetId: null,
            mockExamName: null,
            plannedSeconds: 900,
            completedSeconds: 120,
            status: 'PENDING',
            sortOrder: 0,
            immutable: true,
            courseId: 'course-1',
            courseName: '行测',
          ),
        ],
      );
    }
    return DailyPlanData(
      id: 'plan-today',
      date: date,
      status: 'ACTIVE',
      version: 1,
      items: const [
        PlanItemData(
          id: 'today-1',
          itemType: 'VIDEO',
          title: '数量关系',
          mediaItemId: 'lesson-3',
          mockExamPresetId: null,
          mockExamName: null,
          plannedSeconds: 1200,
          completedSeconds: 0,
          status: 'PENDING',
          sortOrder: 0,
          immutable: false,
          courseId: 'course-1',
          courseName: '行测',
        ),
      ],
    );
  }

  @override
  Future<DailyPlanData> saveToday({
    required int expectedVersion,
    required List<BattleOrderDraft> items,
  }) {
    throw UnimplementedError();
  }

  @override
  Future<List<LearningDebtData>> loadDebts() async => const [
    LearningDebtData(
      id: 'debt-1',
      debtType: 'VIDEO_WATCH',
      title: '判断推理强化',
      remainingSeconds: 800,
      originalSeconds: 800,
      baselineCompletedSeconds: 1000,
      status: 'OPEN',
      mediaItemId: 'lesson-debt',
    ),
  ];
}
