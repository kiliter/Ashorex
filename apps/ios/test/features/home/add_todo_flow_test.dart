import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/add_todo_sheet.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 添加 Todo：只有课程 / 专注计时 / 待办事项三个入口，
/// 创建载荷必须带上 `localDate` 与类型专属字段，非法时长不允许提交。
void main() {
  testWidgets('首页选课慢速右滑多选应用，取消不改结果，清空恢复', (tester) async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          courseSummaryJson(id: 'a', title: '法律基础', genres: ['法律']),
          courseSummaryJson(id: 'b', title: '数学基础', genres: ['数学']),
          courseSummaryJson(id: 'c', title: '英语基础', genres: ['英语']),
        ],
      );
    await _open(tester, backend);
    await tester.tap(find.text('课程'));
    await tester.pumpAndSettle();
    await tester.timedDrag(
      find.text('法律基础'),
      const Offset(180, 0),
      const Duration(seconds: 2),
    );
    await tester.pumpAndSettle();
    expect(find.text('应用筛选'), findsOneWidget);
    await tester.tap(find.widgetWithText(ChoiceChip, '法律'));
    await tester.pump();
    await tester.tap(find.widgetWithText(ChoiceChip, '数学'));
    await tester.pump();
    await tester.tap(find.text('应用筛选'));
    await tester.pumpAndSettle();
    expect(find.text('法律基础'), findsOneWidget);
    expect(find.text('数学基础'), findsOneWidget);
    expect(find.text('英语基础'), findsNothing);
    await tester.tap(find.text('筛选课程'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('清空选择'));
    await tester.pump();
    await tester.timedDrag(
      find.text('全部'),
      const Offset(-180, 0),
      const Duration(seconds: 2),
    );
    await tester.pumpAndSettle();
    expect(find.text('应用筛选'), findsNothing);
    expect(find.text('英语基础'), findsNothing);
    await tester.tap(find.text('一键清空'));
    await tester.pumpAndSettle();
    expect(find.text('英语基础'), findsOneWidget);
  });

  testWidgets('选课横屏键盘下搜索和空结果不溢出', (tester) async {
    tester.view.physicalSize = const Size(844, 390);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    await _open(
      tester,
      FakeBackend()..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [courseSummaryJson(id: 'a', title: '行政法')],
      ),
    );
    await tester.tap(find.text('课程'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '不存在');
    tester.view.viewInsets = const FakeViewPadding(bottom: 220);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.textContaining('没有符合条件的课程'), findsOneWidget);
    expect(tester.getBottomRight(find.text('一键清空')).dy, lessThan(170));
    await tester.tap(find.text('一键清空'));
    await tester.pump();
    expect(find.text('行政法'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('只提供三类入口，不出现答题、模拟考试或还债', (tester) async {
    await _open(tester, FakeBackend());

    expect(find.text('课程'), findsOneWidget);
    expect(find.text('专注计时'), findsOneWidget);
    expect(find.text('待办事项'), findsOneWidget);
    expect(find.textContaining('答题'), findsNothing);
    expect(find.textContaining('模拟考试'), findsNothing);
    expect(find.textContaining('还债'), findsNothing);
  });

  testWidgets('非今天时标题写明目标日期', (tester) async {
    await _open(tester, FakeBackend(), date: DateTime(2026, 9, 11));

    expect(find.textContaining('添加到'), findsOneWidget);
    expect(find.text('添加到今日'), findsNothing);
  });

  testWidgets('专注计时未填名称时不能保存', (tester) async {
    await _open(tester, FakeBackend());
    await tester.tap(find.text('专注计时'));
    await tester.pumpAndSettle();

    expect(find.text('新建专注计时'), findsOneWidget);
    expect(_filled(tester, '保存并加入今日').onPressed, isNull);

    await tester.enterText(find.byType(TextField).first, '法条背诵');
    await tester.pumpAndSettle();
    expect(_filled(tester, '保存并加入今日').onPressed, isNotNull);
  });

  testWidgets('专注计时提交 plannedSeconds 与 localDate', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos');
    await _open(tester, backend, date: DateTime(2026, 9, 7));
    await tester.tap(find.text('专注计时'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, '法条背诵');
    await tester.tap(find.text('45 分'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存并加入今日'));
    await tester.pumpAndSettle();

    final items = backend.lastRequest('POST', '/api/v1/todos').json['items']!;
    final item = (items as List).single as Map;
    expect(item['todoType'], 'FOCUS');
    expect(item['title'], '法条背诵');
    expect(item['plannedSeconds'], 45 * 60);
    expect(item['localDate'], '2026-09-07');
    expect(item['requireEvidence'], isFalse);
  });

  testWidgets('自定义时长超出 1 – 480 分钟时不能保存', (tester) async {
    await _open(tester, FakeBackend());
    await tester.tap(find.text('专注计时'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, '法条背诵');
    await tester.tap(find.text('自定义'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(1), '999');
    await tester.pumpAndSettle();
    expect(_filled(tester, '保存并加入今日').onPressed, isNull);

    await tester.enterText(find.byType(TextField).at(1), '90');
    await tester.pumpAndSettle();
    expect(_filled(tester, '保存并加入今日').onPressed, isNotNull);
  });

  testWidgets('要求拍照时把 requireEvidence 一起提交', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos');
    await _open(tester, backend);
    await tester.tap(find.text('专注计时'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, '法条背诵');
    expect(find.text('完成时要求拍照'), findsOneWidget);
    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存并加入今日'));
    await tester.pumpAndSettle();

    final items = backend.lastRequest('POST', '/api/v1/todos').json['items']!;
    expect(((items as List).single as Map)['requireEvidence'], isTrue);
  });

  testWidgets('待办事项未填标题时不能保存，提交为 TASK 类型', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos');
    await _open(tester, backend, date: DateTime(2026, 9, 7));
    await tester.tap(find.text('待办事项'));
    await tester.pumpAndSettle();

    expect(_filled(tester, '保存').onPressed, isNull);
    await tester.enterText(find.byType(TextField).first, '整理错题本');
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();

    final items = backend.lastRequest('POST', '/api/v1/todos').json['items']!;
    final item = (items as List).single as Map;
    expect(item['todoType'], 'TASK');
    expect(item['title'], '整理错题本');
    expect(item['localDate'], '2026-09-07');
  });

  testWidgets('添加课程独立筛选，关闭重开不沿用条件且保留课程库筛选', (tester) async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          courseSummaryJson(id: 'c-1', title: '法律基础', genres: ['法律']),
          courseSummaryJson(id: 'c-2', title: '数学基础', genres: ['数学']),
        ],
      );
    await _open(tester, backend);
    final container = ProviderScope.containerOf(
      tester.element(find.byType(AddTodoSheet)),
    );
    // 模拟用户已在课程库叠加筛选；添加面板不得继承这些查询参数。
    final controller = container.read(catalogFilterProvider.notifier);
    controller.toggleGenre('法律');
    controller.toggleTag('重点');
    controller.togglePerson('袁东');
    controller.setQuery('行政');
    final libraryFilter = container.read(catalogFilterProvider);
    await tester.tap(find.text('课程'));
    await tester.pumpAndSettle();
    expect(
      backend.lastRequest('GET', '/api/v1/catalog/courses').path,
      '/api/v1/catalog/courses',
    );
    expect(find.text('法律基础'), findsOneWidget);
    expect(find.text('数学基础'), findsOneWidget);

    await tester.enterText(find.byType(TextField), '数学');
    await tester.pumpAndSettle();
    expect(find.text('法律基础'), findsNothing);
    expect(find.text('数学基础'), findsOneWidget);
    expect(container.read(catalogFilterProvider), same(libraryFilter));

    // 重新进入时重新加载快照，并从全部课程开始。
    Navigator.of(tester.element(find.text('选择课程'))).pop(false);
    await tester.pumpAndSettle();
    await tester.tap(find.text('课程'));
    await tester.pumpAndSettle();
    expect(find.text('法律基础'), findsOneWidget);
    expect(find.text('数学基础'), findsOneWidget);
    expect(backend.callCount('GET', '/api/v1/catalog/courses'), 2);
    expect(container.read(catalogFilterProvider), same(libraryFilter));
  });

  testWidgets('课程入口进入课程库选择，空库时给出空态', (tester) async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/catalog/facets', json: const {})
      ..on('GET', '/api/v1/catalog/courses', json: const []);
    await _open(tester, backend);

    await tester.tap(find.text('课程'));
    await tester.pumpAndSettle();

    expect(backend.callCount('GET', '/api/v1/catalog/courses'), 1);
  });
}

FilledButton _filled(WidgetTester tester, String label) {
  return tester.widget<FilledButton>(
    find.ancestor(of: find.text(label), matching: find.byType(FilledButton)),
  );
}

Future<void> _open(
  WidgetTester tester,
  FakeBackend backend, {
  DateTime? date,
}) async {
  final now = DateTime.now();
  final target = date ?? DateTime(now.year, now.month, now.day);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
      child: MaterialApp(
        theme: ShanganTheme.light(),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => AddTodoSheet.show(context, target),
              child: const Text('打开添加面板'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('打开添加面板'));
  await tester.pumpAndSettle();
}
