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

  testWidgets('首页模式只列出进行中课时，待开始和已完成都不出现', (tester) async {
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
                    id: 'todo-1',
                    itemType: 'VIDEO',
                    title: '未看完课时',
                    mediaItemId: 'lesson-1',
                    mockExamPresetId: null,
                    mockExamName: null,
                    plannedSeconds: 600,
                    completedSeconds: 120,
                    status: 'PENDING',
                    sortOrder: 1,
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
                ],
              ),
              grouped: true,
              readOnly: false,
              showDebtMarks: false,
              inProgressOnly: true,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('已看完课时'), findsNothing);
    expect(find.text('待开始课时'), findsNothing);
    expect(find.text('未看完课时'), findsOneWidget);
    expect(find.textContaining('进行中'), findsOneWidget);
    expect(find.byType(ShanganStatusTag), findsOneWidget);
  });
}
