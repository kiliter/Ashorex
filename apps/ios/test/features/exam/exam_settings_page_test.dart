import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';
import 'package:shangan_ios/features/exam/presentation/exam_settings_page.dart';

void main() {
  testWidgets('考试列表按到期日排列并为每场考试提供删除入口', (tester) async {
    await tester.pumpWidget(_app(_ExamRepository()));
    await tester.pumpAndSettle();

    expect(find.text('2026 国考'), findsOneWidget);
    expect(find.text('省考'), findsOneWidget);
    expect(find.byKey(const Key('deleteExamGoal-goal-1')), findsOneWidget);
    expect(find.byKey(const Key('deleteExamGoal-goal-2')), findsOneWidget);
  });

  testWidgets('取消确认时不删除考试目标', (tester) async {
    final repository = _ExamRepository();
    await tester.pumpWidget(_app(repository));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('deleteExamGoal-goal-1')));
    await tester.pumpAndSettle();
    expect(find.text('删除考试目标'), findsOneWidget);
    expect(find.textContaining('已完成的学习记录'), findsOneWidget);

    await tester.tap(find.byKey(const Key('cancelDeleteExamGoal')));
    await tester.pumpAndSettle();

    expect(repository.deletedGoalIds, isEmpty);
    expect(find.text('2026 国考'), findsOneWidget);
  });

  testWidgets('确认后删除考试目标并刷新列表', (tester) async {
    final repository = _ExamRepository();
    await tester.pumpWidget(_app(repository));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('deleteExamGoal-goal-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmDeleteExamGoal')));
    await tester.pumpAndSettle();

    expect(repository.deletedGoalIds, ['goal-1']);
    expect(find.text('2026 国考'), findsNothing);
    expect(find.text('省考'), findsOneWidget);
  });

  testWidgets('删除最后一场考试后展示空态提示', (tester) async {
    final repository = _ExamRepository(onlyFirstGoal: true);
    await tester.pumpWidget(_app(repository));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('deleteExamGoal-goal-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmDeleteExamGoal')));
    await tester.pumpAndSettle();

    expect(repository.deletedGoalIds, ['goal-1']);
    expect(find.text('还没有考试目标'), findsOneWidget);
  });
}

Widget _app(ExamRepository repository) => ProviderScope(
  overrides: [examRepositoryProvider.overrideWithValue(repository)],
  child: const MaterialApp(home: ExamSettingsPage()),
);

/// 记录删除调用并在删除后返回剩余考试的仓库替身。
final class _ExamRepository implements ExamRepository {
  _ExamRepository({this.onlyFirstGoal = false});

  final bool onlyFirstGoal;
  final List<String> deletedGoalIds = [];

  @override
  Future<List<ExamGoal>> listGoals() async => [
    if (!deletedGoalIds.contains('goal-1'))
      ExamGoal(
        id: 'goal-1',
        name: '2026 国考',
        examDate: DateTime(2026, 11, 29),
        targetCompletionDate: DateTime(2026, 11, 15),
        reviewBufferDays: 14,
        timezone: 'Asia/Shanghai',
        courseIds: const ['course-1'],
      ),
    if (!onlyFirstGoal && !deletedGoalIds.contains('goal-2'))
      ExamGoal(
        id: 'goal-2',
        name: '省考',
        examDate: DateTime(2027, 3, 20),
        targetCompletionDate: DateTime(2027, 3, 1),
        reviewBufferDays: 19,
        timezone: 'Asia/Shanghai',
        courseIds: const ['course-2'],
      ),
  ];

  @override
  Future<void> deleteGoal(String goalId) async {
    deletedGoalIds.add(goalId);
  }

  @override
  Future<ExamGoal?> loadGoal() async => (await listGoals()).firstOrNull;

  @override
  Future<ExamGoal> loadGoalById(String goalId) {
    throw UnimplementedError();
  }

  @override
  Future<ExamGoal> saveGoal(ExamGoalDraft draft) {
    throw UnimplementedError();
  }

  @override
  Future<ExamGoal> updateGoal(String goalId, ExamGoalDraft draft) {
    throw UnimplementedError();
  }
}
