import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// 目标看板：倒计时天数直接采用服务端下发值，主目标唯一并置顶，
/// 紧急度不能只靠颜色表达，空态必须给出可点击的建立入口。
void main() {
  testWidgets('空目标时展示可点击的新建引导', (tester) async {
    var tapped = 0;
    await _pump(tester, goals: const [], onManage: () => tapped += 1);

    expect(find.text('还没有考试目标，点这里新建一个倒计时'), findsOneWidget);
    await tester.tap(find.text('还没有考试目标，点这里新建一个倒计时'));
    expect(tapped, 1);
  });

  testWidgets('主目标置顶展示倒计时天数与考试日期', (tester) async {
    await _pump(
      tester,
      goals: [
        _goal(id: 'g-1', name: '法考客观题', daysRemaining: 13, primary: true),
      ],
    );

    expect(find.text('我的目标 · 1 个'), findsOneWidget);
    expect(find.text('13'), findsOneWidget);
    expect(find.text('天'), findsOneWidget);
    expect(find.text('法考客观题'), findsOneWidget);
    expect(find.textContaining('主目标'), findsOneWidget);
  });

  testWidgets('多个目标时非主目标以次级磁贴展示', (tester) async {
    await _pump(
      tester,
      goals: [
        _goal(id: 'g-1', name: '法考客观题', daysRemaining: 13, primary: true),
        _goal(
          id: 'g-2',
          name: '公考省考',
          daysRemaining: 40,
          primary: false,
          urgency: GoalUrgency.normal,
        ),
      ],
    );

    expect(find.text('我的目标 · 2 个'), findsOneWidget);
    expect(find.text('法考客观题'), findsOneWidget);
    expect(find.text('公考省考'), findsOneWidget);
  });

  testWidgets('没有标记主目标时取第一个作为主目标，不出现空看板', (tester) async {
    await _pump(
      tester,
      goals: [
        _goal(id: 'g-1', name: '第一目标', daysRemaining: 5, primary: false),
        _goal(id: 'g-2', name: '第二目标', daysRemaining: 9, primary: false),
      ],
    );

    expect(find.text('第一目标'), findsOneWidget);
    expect(find.textContaining('主目标'), findsOneWidget);
  });

  testWidgets('已过期目标的天数为负值时照样按服务端结果展示', (tester) async {
    await _pump(
      tester,
      goals: [
        _goal(
          id: 'g-1',
          name: '已过期目标',
          daysRemaining: -3,
          primary: true,
          urgency: GoalUrgency.expired,
        ),
      ],
    );

    expect(find.text('-3'), findsOneWidget);
  });

  testWidgets('管理入口缺省时不渲染管理按钮', (tester) async {
    await _pump(
      tester,
      goals: [_goal(id: 'g-1', daysRemaining: 13, primary: true)],
    );

    expect(find.text('管理'), findsNothing);
  });
}

ExamGoal _goal({
  required String id,
  String name = '法考客观题',
  int daysRemaining = 13,
  bool primary = true,
  GoalUrgency urgency = GoalUrgency.normal,
}) {
  return ExamGoal(
    id: id,
    name: name,
    examDate: DateTime(2026, 9, 20),
    note: '',
    primary: primary,
    daysRemaining: daysRemaining,
    urgency: urgency,
  );
}

Future<void> _pump(
  WidgetTester tester, {
  required List<ExamGoal> goals,
  VoidCallback? onManage,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: GoalBoard(goals: goals, onManage: onManage),
        ),
      ),
    ),
  );
  await tester.pump();
}
