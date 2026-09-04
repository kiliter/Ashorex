import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/planning/presentation/battle_order_widgets.dart';

void main() {
  testWidgets('课时超过三条时作战单内部出现滚动条，课程名用强调色', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: BattleOrderDayPanel(
              plan: DailyPlanData(
                id: 'plan-1',
                date: DateTime(2026, 9, 3),
                status: 'ACTIVE',
                version: 1,
                items: [
                  for (var i = 1; i <= 6; i++)
                    PlanItemData(
                      id: 'item-$i',
                      itemType: 'VIDEO',
                      title: '课时 $i',
                      mediaItemId: 'lesson-$i',
                      mockExamPresetId: null,
                      mockExamName: null,
                      plannedSeconds: 600,
                      completedSeconds: 0,
                      status: 'PENDING',
                      sortOrder: i,
                      immutable: false,
                      courseId: 'course-1',
                      courseName: '三农知识',
                    ),
                ],
              ),
              grouped: true,
              readOnly: false,
              showDebtMarks: false,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('battleOrderScrollbar')), findsOneWidget);
    expect(find.text('课时 1'), findsOneWidget);
    expect(find.text('三农知识'), findsOneWidget);
    final title = tester.widget<Text>(find.text('三农知识'));
    expect(title.style?.color, ShanganColors.course);
    expect(find.byKey(const Key('battleOrderPlay-item-1')), findsOneWidget);
    expect(find.byType(ShanganStatusTag), findsWidgets);
  });

  testWidgets('历史未完成课程名标红，今日欠债组单独成组且课程序号从 1 起', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: BattleOrderDayPanel(
              plan: DailyPlanData(
                id: 'plan-1',
                date: DateTime(2026, 9, 2),
                status: 'CLOSED_WITH_DEBT',
                version: 1,
                items: const [
                  PlanItemData(
                    id: 'item-1',
                    itemType: 'VIDEO',
                    title: '言语理解',
                    mediaItemId: 'lesson-1',
                    mockExamPresetId: null,
                    mockExamName: null,
                    plannedSeconds: 900,
                    completedSeconds: 120,
                    status: 'PENDING',
                    sortOrder: 0,
                    immutable: true,
                    courseId: 'course-1',
                    courseName: '三农知识',
                  ),
                ],
              ),
              grouped: true,
              readOnly: true,
              showDebtMarks: true,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('学习欠债'), findsNothing);
    expect(find.textContaining('课程 1'), findsOneWidget);
    final title = tester.widget<Text>(find.text('三农知识'));
    expect(title.style?.color, ShanganColors.red);
    expect(find.textContaining('欠债'), findsWidgets);
  });

  testWidgets('首页接续队列列出欠债和今日未完成，进行中排在最前，已完成不出现', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: BattleOrderDayPanel(
              plan: DailyPlanData(
                id: 'plan-1',
                date: DateTime(2026, 9, 3),
                status: 'ACTIVE',
                version: 1,
                items: const [
                  PlanItemData(
                    id: 'done-1',
                    itemType: 'VIDEO',
                    title: '已看完课时',
                    mediaItemId: 'lesson-0',
                    mockExamPresetId: null,
                    mockExamName: null,
                    plannedSeconds: 600,
                    completedSeconds: 600,
                    status: 'COMPLETED',
                    sortOrder: 0,
                    immutable: false,
                    courseId: 'course-1',
                    courseName: '三农知识',
                  ),
                  PlanItemData(
                    id: 'pending-1',
                    itemType: 'VIDEO',
                    title: '待开始课时',
                    mediaItemId: 'lesson-2',
                    mockExamPresetId: null,
                    mockExamName: null,
                    plannedSeconds: 600,
                    completedSeconds: 0,
                    status: 'PENDING',
                    sortOrder: 2,
                    immutable: false,
                    courseId: 'course-1',
                    courseName: '三农知识',
                  ),
                  PlanItemData(
                    id: 'debt-1',
                    itemType: 'DEBT_REPAYMENT',
                    title: '历史欠债课时',
                    mediaItemId: 'lesson-3',
                    mockExamPresetId: null,
                    mockExamName: null,
                    plannedSeconds: 800,
                    completedSeconds: 0,
                    status: 'PENDING',
                    sortOrder: 1,
                    immutable: true,
                    debtId: 'debt-1',
                  ),
                  PlanItemData(
                    id: 'todo-1',
                    itemType: 'VIDEO',
                    title: '未看完课时',
                    mediaItemId: 'lesson-1',
                    mockExamPresetId: null,
                    mockExamName: null,
                    plannedSeconds: 600,
                    completedSeconds: 120,
                    status: 'PENDING',
                    sortOrder: 3,
                    immutable: false,
                    courseId: 'course-1',
                    courseName: '三农知识',
                  ),
                ],
              ),
              grouped: false,
              readOnly: false,
              showDebtMarks: false,
              resumeQueue: true,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('已看完课时'), findsNothing);
    expect(find.text('未看完课时'), findsOneWidget);
    expect(find.text('历史欠债课时'), findsOneWidget);
    expect(find.text('待开始课时'), findsOneWidget);
    expect(
      tester.getTopLeft(find.text('历史欠债课时')).dy,
      lessThan(tester.getTopLeft(find.text('未看完课时')).dy),
    );
    expect(
      tester.getTopLeft(find.text('未看完课时')).dy,
      lessThan(tester.getTopLeft(find.text('待开始课时')).dy),
    );
    expect(find.textContaining('进行中'), findsOneWidget);
  });

  test('未入单视频欠债按基线回显整集进度，续播不带合成计划 ID', () {
    final merged = mergeOpenDebts(
      DailyPlanData(
        id: 'plan-1',
        date: DateTime(2026, 9, 4),
        status: 'ACTIVE',
        version: 1,
        items: const [],
      ),
      const [
        LearningDebtData(
          id: 'debt-1',
          debtType: 'VIDEO_WATCH',
          title: '判断推理强化',
          remainingSeconds: 800,
          originalSeconds: 800,
          baselineCompletedSeconds: 1000,
          status: 'OPEN',
          mediaItemId: 'lesson-9',
        ),
      ],
    );
    final item = merged.items.single;
    expect(item.plannedSeconds, 1800);
    expect(item.completedSeconds, 1000);
    expect(battleOrderItemPlayable(item), isTrue);
    expect(firstResumableItem(merged.items)?.mediaItemId, 'lesson-9');
    expect(battleOrderItemStatus(item).label, '进行中');
    expect(battleOrderItemSubtitle(item), contains('已观看 55%'));
    final uri = battleOrderPlaybackUri(item)!;
    expect(uri.path, '/player/lesson-9');
    expect(uri.queryParameters['planItemId'], isNull);
    expect(uri.queryParameters['title'], '判断推理强化');
  });

  test('已入单还债任务也用欠债账本回显整集进度', () {
    final merged = mergeOpenDebts(
      DailyPlanData(
        id: 'plan-1',
        date: DateTime(2026, 9, 4),
        status: 'ACTIVE',
        version: 1,
        items: const [
          PlanItemData(
            id: 'item-debt',
            itemType: 'DEBT_REPAYMENT',
            title: '还债：判断推理强化',
            mediaItemId: 'lesson-9',
            mockExamPresetId: null,
            mockExamName: null,
            plannedSeconds: 800,
            completedSeconds: 0,
            status: 'PENDING',
            sortOrder: 0,
            immutable: true,
            debtId: 'debt-1',
          ),
        ],
      ),
      const [
        LearningDebtData(
          id: 'debt-1',
          debtType: 'VIDEO_WATCH',
          title: '判断推理强化',
          remainingSeconds: 800,
          originalSeconds: 800,
          baselineCompletedSeconds: 1000,
          status: 'OPEN',
          mediaItemId: 'lesson-9',
        ),
      ],
    );
    final item = merged.items.single;
    expect(item.id, 'item-debt');
    expect(item.plannedSeconds, 1800);
    expect(item.completedSeconds, 1000);
    expect(item.sourceDebtType, 'VIDEO_WATCH');
  });

  test('今日作战单已有同一课时则不再插入欠债条目', () {
    final merged = mergeOpenDebts(
      DailyPlanData(
        id: 'plan-1',
        date: DateTime(2026, 9, 4),
        status: 'ACTIVE',
        version: 1,
        items: const [
          PlanItemData(
            id: 'item-1',
            itemType: 'VIDEO',
            title: '判断推理强化',
            mediaItemId: 'lesson-9',
            mockExamPresetId: null,
            mockExamName: null,
            plannedSeconds: 1800,
            completedSeconds: 1000,
            status: 'PENDING',
            sortOrder: 0,
            immutable: true,
            courseId: 'course-1',
            courseName: '行测',
          ),
        ],
      ),
      const [
        LearningDebtData(
          id: 'debt-1',
          debtType: 'VIDEO_WATCH',
          title: '判断推理强化',
          remainingSeconds: 800,
          originalSeconds: 800,
          baselineCompletedSeconds: 1000,
          status: 'OPEN',
          mediaItemId: 'lesson-9',
        ),
      ],
    );
    expect(merged.items, hasLength(1));
    expect(merged.items.single.id, 'item-1');
  });

  test('复习入口不会吞掉同一课时的答题欠债', () {
    final merged = mergeOpenDebts(
      DailyPlanData(
        id: 'plan-1',
        date: DateTime(2026, 9, 4),
        status: 'ACTIVE',
        version: 1,
        items: const [
          PlanItemData(
            id: 'review-1',
            itemType: 'REVIEW_SHORTCUT',
            title: '判断推理强化',
            mediaItemId: 'lesson-9',
            mockExamPresetId: null,
            mockExamName: null,
            plannedSeconds: 1800,
            completedSeconds: 1800,
            status: 'PENDING',
            sortOrder: 0,
            immutable: false,
          ),
        ],
      ),
      const [
        LearningDebtData(
          id: 'debt-quiz',
          debtType: 'QUIZ',
          title: '判断推理强化课后题',
          remainingSeconds: 600,
          originalSeconds: 600,
          status: 'OPEN',
          mediaItemId: 'lesson-9',
        ),
      ],
    );
    expect(merged.items, hasLength(2));
    expect(merged.items.any((item) => item.debtId == 'debt-quiz'), isTrue);
  });

  test('模拟考试按会话状态显示考试中、待传试卷或已完成', () {
    PlanItemData exam({String? session, String status = 'PENDING'}) =>
        PlanItemData(
          id: 'exam-1',
          itemType: 'MOCK_EXAM',
          title: '行测',
          mediaItemId: null,
          mockExamPresetId: 'preset-1',
          mockExamName: '行测',
          plannedSeconds: 7200,
          completedSeconds: 0,
          status: status,
          sortOrder: 0,
          immutable: true,
          mockExamSessionStatus: session,
        );

    expect(battleOrderItemStatus(exam()).label, '待开始');
    expect(battleOrderItemStatus(exam(session: 'RUNNING')).label, '考试中');
    expect(
      battleOrderItemStatus(exam(session: 'AWAITING_UPLOAD')).label,
      '待传试卷',
    );
    expect(battleOrderItemStatus(exam(session: 'COMPLETED')).label, '已完成');
  });

  testWidgets('首页接续队列里未入单欠债可以点播放', (tester) async {
    final plan = mergeOpenDebts(
      DailyPlanData(
        id: 'plan-1',
        date: DateTime(2026, 9, 4),
        status: 'ACTIVE',
        version: 1,
        items: const [],
      ),
      const [
        LearningDebtData(
          id: 'debt-1',
          debtType: 'VIDEO_WATCH',
          title: '判断推理强化',
          remainingSeconds: 800,
          originalSeconds: 800,
          baselineCompletedSeconds: 1000,
          status: 'OPEN',
          mediaItemId: 'lesson-9',
        ),
      ],
    );
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: BattleOrderDayPanel(
              plan: plan,
              grouped: false,
              readOnly: false,
              showDebtMarks: false,
              resumeQueue: true,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('判断推理强化'), findsOneWidget);
    expect(find.textContaining('进行中'), findsOneWidget);
    expect(find.textContaining('已观看 55%'), findsOneWidget);
    final play = tester.widget<IconButton>(
      find.byKey(const Key('battleOrderPlay-debt:debt-1')),
    );
    expect(play.onPressed, isNotNull);
  });
}
