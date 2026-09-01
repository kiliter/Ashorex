import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/focus/data/mock_exam_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/planning/presentation/plan_page.dart';

void main() {
  testWidgets('作战单内容未修改时不允许产生空版本', (tester) async {
    final plans = _PlanRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          planRepositoryProvider.overrideWithValue(plans),
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
          mockExamRepositoryProvider.overrideWithValue(_MockExamRepository()),
        ],
        child: const MaterialApp(home: PlanPage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('作战单编排区'), findsOneWidget);
    expect(find.text('资料分析'), findsOneWidget);
    final saveButton = tester.widget<FilledButton>(
      find.byKey(const Key('saveBattleOrder')),
    );
    expect(saveButton.onPressed, isNull);
    await tester.tap(find.byKey(const Key('saveBattleOrder')));
    await tester.pumpAndSettle();

    expect(plans.savedExpectedVersion, isNull);
    expect(find.textContaining('作战单已保存为'), findsNothing);
  });

  testWidgets('课程默认折叠，展开后区分观看状态并支持模糊搜索', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          planRepositoryProvider.overrideWithValue(_PlanRepository()),
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
          mockExamRepositoryProvider.overrideWithValue(_MockExamRepository()),
        ],
        child: const MaterialApp(home: PlanPage()),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('openBattleOrderPicker')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('battleOrderLessonSearch')), findsOneWidget);
    expect(find.text('判断推理强化'), findsNothing);
    expect(find.text('2 个课时 · 已观看 2 个'), findsOneWidget);
    await tester.tap(find.text('公务员考试'));
    await tester.pumpAndSettle();
    expect(find.text('已看完'), findsOneWidget);
    expect(find.text('已观看 35%'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('battleOrderLessonSearch')),
      '判断',
    );
    await tester.pumpAndSettle();
    final pickerSheet = find.byType(BottomSheet);
    expect(find.text('判断推理强化'), findsOneWidget);
    expect(
      find.descendant(of: pickerSheet, matching: find.text('资料分析')),
      findsNothing,
    );

    await tester.tap(find.text('模拟考试'));
    await tester.pumpAndSettle();
    expect(find.text('考试预置设置'), findsOneWidget);
    expect(find.text('行测模拟卷'), findsOneWidget);
    expect(find.text('2 小时 00 分'), findsOneWidget);
  });

  testWidgets('已选择课时可再次点击取消且课时按视频顺序保存', (tester) async {
    final plans = _PlanRepository(initialItems: const []);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          planRepositoryProvider.overrideWithValue(plans),
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
          mockExamRepositoryProvider.overrideWithValue(_MockExamRepository()),
        ],
        child: const MaterialApp(home: PlanPage()),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('openBattleOrderPicker')));
    await tester.pumpAndSettle();
    final pickerSheet = find.byType(BottomSheet);
    Finder pickerText(String value) =>
        find.descendant(of: pickerSheet, matching: find.text(value));
    await tester.tap(pickerText('公务员考试'));
    await tester.pumpAndSettle();
    await tester.tap(pickerText('判断推理强化'));
    await tester.pumpAndSettle();
    await tester.tap(pickerText('资料分析'));
    await tester.pumpAndSettle();
    await tester.tap(pickerText('判断推理强化'));
    await tester.pumpAndSettle();
    await tester.tap(pickerText('判断推理强化'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('完成'));
    await tester.pumpAndSettle();

    expect(find.byTooltip('上移'), findsNothing);
    expect(find.byTooltip('下移'), findsNothing);
    await tester.tap(find.byKey(const Key('saveBattleOrder')));
    await tester.pumpAndSettle();
    expect(plans.savedItems.map((item) => item.mediaItemId), [
      'lesson-1',
      'lesson-2',
    ]);
  });

  testWidgets('连续点击保存只提交一次且只展示一个最新提示', (tester) async {
    final plans = _PlanRepository(deferSave: true);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          planRepositoryProvider.overrideWithValue(plans),
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
          mockExamRepositoryProvider.overrideWithValue(_MockExamRepository()),
        ],
        child: const MaterialApp(home: PlanPage()),
      ),
    );
    await tester.pumpAndSettle();

    // 先产生一次真实修改，再模拟用户在保存请求返回前连续点击。
    await tester.tap(find.byTooltip('从作战单删除'));
    await tester.pump();
    final saveButton = find.byKey(const Key('saveBattleOrder'));
    await tester.tap(saveButton);
    await tester.tap(saveButton);
    await tester.tap(saveButton);

    expect(plans.saveCallCount, 1);
    plans.completeSave();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('作战单已保存为第 5 版'), findsOneWidget);
    expect(find.byType(SnackBar), findsOneWidget);
  });
}

/// 测试仓库记录页面提交的版本号和完整项目快照。
final class _PlanRepository implements PlanRepository {
  _PlanRepository({
    this.deferSave = false,
    this.initialItems = const [
      PlanItemData(
        id: 'item-1',
        itemType: 'VIDEO',
        title: '资料分析',
        mediaItemId: 'lesson-1',
        mockExamPresetId: null,
        mockExamName: null,
        plannedSeconds: 600,
        completedSeconds: 0,
        status: 'PENDING',
        sortOrder: 0,
        immutable: false,
      ),
    ],
  });

  final bool deferSave;
  final List<PlanItemData> initialItems;
  final Completer<void> _saveGate = Completer<void>();
  int? savedExpectedVersion;
  int saveCallCount = 0;
  List<BattleOrderDraft> savedItems = const [];

  /// 释放测试中的延迟保存请求，用来验证页面的连续点击防重入逻辑。
  void completeSave() {
    if (!_saveGate.isCompleted) _saveGate.complete();
  }

  DailyPlanData _plan(int version) => DailyPlanData(
    id: 'plan-1',
    date: DateTime(2026, 9),
    status: 'ACTIVE',
    version: version,
    items: initialItems,
  );

  @override
  Future<DailyPlanData> loadToday() async => _plan(4);

  @override
  Future<DailyPlanData> saveToday({
    required int expectedVersion,
    required List<BattleOrderDraft> items,
  }) async {
    saveCallCount += 1;
    savedExpectedVersion = expectedVersion;
    savedItems = List.unmodifiable(items);
    if (deferSave) await _saveGate.future;
    return _plan(5);
  }

  @override
  Future<List<LearningDebtData>> loadDebts() async => const [];
}

final class _CatalogRepository implements CatalogRepository {
  @override
  Future<List<CourseSummary>> listCourses() async => const [
    CourseSummary(id: 'course-1', name: '公务员考试', description: '行测专项课程'),
  ];

  @override
  Future<CourseDetail> loadCourse(String courseId) async => const CourseDetail(
    id: 'course-1',
    name: '公务员考试',
    description: '行测专项课程',
    lessons: [
      LessonSummary(
        id: 'lesson-1',
        courseId: 'course-1',
        title: '资料分析',
        durationMs: 600000,
        sortOrder: 0,
        maxVerifiedPositionMs: 0,
        progressPercent: 100,
        learningStatus: 'COMPLETED',
        summaryAvailable: false,
      ),
      LessonSummary(
        id: 'lesson-2',
        courseId: 'course-1',
        title: '判断推理强化',
        durationMs: 900000,
        sortOrder: 1,
        maxVerifiedPositionMs: 0,
        progressPercent: 35,
        learningStatus: 'IN_PROGRESS',
        summaryAvailable: false,
      ),
    ],
  );

  @override
  Future<LessonSummary> loadLesson(String lessonId) async => (await loadCourse(
    'course-1',
  )).lessons.firstWhere((lesson) => lesson.id == lessonId);

  @override
  Future<LessonStudyContentData> loadStudyContent(String lessonId) {
    throw UnimplementedError();
  }
}

final class _MockExamRepository implements MockExamRepository {
  @override
  Future<MockExamPresetData> createPreset(String name, int seconds) {
    throw UnimplementedError();
  }

  @override
  Future<void> deletePreset(String id) {
    throw UnimplementedError();
  }

  @override
  Future<List<MockExamPresetData>> listPresets() async => const [
    MockExamPresetData(
      id: 'preset-1',
      name: '行测模拟卷',
      durationSeconds: 7200,
      sortOrder: 0,
    ),
  ];

  @override
  Future<MockExamSessionData> load(String sessionId) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamSessionData> start(String planItemId) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamSessionData> submitEarly(String sessionId) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamPresetData> updatePreset(
    MockExamPresetData preset,
    String name,
    int seconds,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamSessionData> upload(
    String sessionId,
    String filename,
    List<int> bytes,
  ) {
    throw UnimplementedError();
  }
}
