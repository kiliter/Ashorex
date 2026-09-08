import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/catalog/presentation/library_page.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 验证用户输入的实际过滤时机及固定区位置，不依赖计时器实现细节。
void main() {
  testWidgets('慢速右滑后多选并应用会改变课程结果，清空恢复', (tester) async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          courseSummaryJson(id: 'a', title: '法律基础', genres: ['法律']),
          courseSummaryJson(id: 'b', title: '数学基础', genres: ['数学']),
          courseSummaryJson(id: 'c', title: '英语基础', genres: ['英语']),
        ],
      )
      ..on(
        'GET',
        '/api/v1/catalog/facets',
        json: {
          'genres': [
            for (final v in ['法律', '数学', '英语']) {'value': v, 'courseCount': 1},
          ],
          'people': [],
          'tags': [],
          'years': [],
        },
      );
    await _pump(tester, backend);
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
    await tester.tap(find.text('一键清空'));
    await tester.pumpAndSettle();
    expect(find.text('英语基础'), findsOneWidget);
  });

  testWidgets('课程搜索300ms防抖，清空即时生效，滚动固定头部', (tester) async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          courseSummaryJson(id: 'a', title: '行政法'),
          courseSummaryJson(id: 'b', title: '民法'),
          for (var i = 0; i < 15; i++)
            courseSummaryJson(id: 'c$i', title: '其他课程$i'),
        ],
      );
    await _pump(tester, backend);
    await tester.tap(find.bySemanticsLabel('搜索课程'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '行政');
    await tester.pump(const Duration(milliseconds: 299));
    expect(find.text('民法'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 1));
    expect(find.text('民法'), findsNothing);
    expect(find.text('行政法'), findsOneWidget);
    await tester.tap(find.text('一键清空'));
    await tester.pump();
    expect(find.text('民法'), findsOneWidget);
    final header = tester.getTopLeft(find.text('课程库'));
    final filters = tester.getTopLeft(find.text('一键清空'));
    await tester.drag(find.byType(ListView).first, const Offset(0, -300));
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(find.text('课程库')), header);
    expect(tester.getTopLeft(find.text('一键清空')), filters);
    expect(backend.callCount('GET', '/api/v1/catalog/courses'), 1);
  });
  testWidgets('横屏键盘展开保留固定筛选和课程结果', (tester) async {
    tester.view.physicalSize = const Size(844, 390);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    await _pump(
      tester,
      FakeBackend()..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [courseSummaryJson(id: 'a', title: '行政法')],
      ),
    );
    await tester.tap(find.bySemanticsLabel('搜索课程'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '行政');
    tester.view.viewInsets = const FakeViewPadding(bottom: 220);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(tester.getBottomRight(find.text('一键清空')).dy, lessThan(170));
    expect(find.text('行政法'), findsOneWidget);
  });
  testWidgets('筛选元数据失败可提示并重试', (tester) async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/catalog/courses', json: <Object>[])
      ..on('GET', '/api/v1/catalog/facets', status: 503);
    await _pump(tester, backend);
    await tester.tap(find.bySemanticsLabel('展开筛选'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('筛选条件加载失败，请重试'), findsOneWidget);
    await tester.tap(find.byTooltip('关闭提示'));
    await tester.pump();
    backend.on(
      'GET',
      '/api/v1/catalog/facets',
      json: {'genres': [], 'people': [], 'tags': [], 'years': []},
    );
    await tester.tap(find.bySemanticsLabel('展开筛选'));
    await tester.pumpAndSettle();
    expect(find.text('应用筛选'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

Future<void> _pump(WidgetTester tester, FakeBackend backend) async {
  await tester.pumpWidget(
    ProviderScope(
      retry: (count, error) => null,
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
      child: const MaterialApp(home: Scaffold(body: LibraryPage())),
    ),
  );
  await tester.pumpAndSettle();
}
