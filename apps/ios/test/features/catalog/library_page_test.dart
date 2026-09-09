import 'dart:ui' show SemanticsAction;
import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/catalog/presentation/library_page.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 验证用户输入的实际过滤时机及固定区位置，不依赖计时器实现细节。
void main() {
  testWidgets('大量不同流派也只构建可视区域', (tester) async {
    await _pump(
      tester,
      FakeBackend()..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          for (var i = 0; i < 300; i++)
            courseSummaryJson(id: 'c$i', title: '课程$i', genres: ['流派$i']),
        ],
      ),
    );
    expect(find.text('课程0'), findsOneWidget);
    expect(find.text('课程299'), findsNothing);
    expect(find.byIcon(Icons.folder_rounded), findsNothing);
  });

  testWidgets('人物文件夹进入对应课程，未标注人物也可浏览', (tester) async {
    await _pump(
      tester,
      FakeBackend()..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          courseSummaryJson(id: 'a', title: '人物甲课程', people: ['人物甲']),
          courseSummaryJson(id: 'b', title: '无人物课程', people: []),
        ],
      ),
    );
    await tester.tap(find.text('按人物'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('人物甲'));
    await tester.pumpAndSettle();
    expect(find.text('人物甲课程'), findsOneWidget);
    expect(find.text('无人物课程'), findsNothing);
    await tester.tap(find.text('一键清空'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('按人物'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('未标注人物'));
    await tester.pumpAndSettle();
    expect(find.text('无人物课程'), findsOneWidget);
    expect(find.text('人物甲课程'), findsNothing);
  });

  testWidgets('大字体窄屏减少列数且保留筛选和完整名称语义', (tester) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _pump(
      tester,
      FakeBackend()..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          courseSummaryJson(id: 'a', title: '这是一个很长的课程名称用于验证大字体布局'),
          courseSummaryJson(id: 'b', title: '第二门课'),
        ],
      ),
      textScale: 2,
    );
    expect(tester.takeException(), isNull);
    expect(find.text('隐藏已看完'), findsOneWidget);
    expect(
      tester.getTopLeft(find.text('这是一个很长的课程名称用于验证大字体布局')).dy,
      tester.getTopLeft(find.text('第二门课')).dy,
    );
    expect(
      find.bySemanticsLabel(RegExp('这是一个很长的课程名称用于验证大字体布局')),
      findsOneWidget,
    );
    expect(
      tester
          .getSemantics(find.bySemanticsLabel(RegExp('这是一个很长的课程名称用于验证大字体布局')))
          .getSemanticsData()
          .hasAction(SemanticsAction.tap),
      isTrue,
    );
  });

  testWidgets('全部被隐藏后可关闭筛选恢复', (tester) async {
    await _pump(
      tester,
      FakeBackend()..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          {...courseSummaryJson(id: 'a', title: '看完课程'), 'fullyWatched': true},
        ],
      ),
    );
    await tester.tap(find.text('隐藏已看完'));
    await tester.pumpAndSettle();
    expect(find.text('没有符合条件的课程'), findsOneWidget);
    await tester.tap(find.text('隐藏已看完'));
    await tester.pumpAndSettle();
    expect(find.text('看完课程'), findsOneWidget);
  });

  testWidgets('隐藏已看完使用服务端结论并与搜索叠加，清空恢复', (tester) async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          {...courseSummaryJson(id: 'a', title: '法律已看完'), 'fullyWatched': true},
          {
            ...courseSummaryJson(id: 'b', title: '法律低目标完成'),
            'completedCount': 24,
            'completedPercent': 100,
          },
          courseSummaryJson(id: 'c', title: '数学未看完'),
        ],
      );
    await _pump(tester, backend);
    await tester.tap(find.text('隐藏已看完'));
    await tester.pumpAndSettle();
    expect(find.text('法律已看完'), findsNothing);
    expect(find.text('法律低目标完成'), findsOneWidget);
    await tester.tap(find.bySemanticsLabel('搜索课程'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '法律');
    await tester.pumpAndSettle();
    expect(find.text('数学未看完'), findsNothing);
    expect(find.text('法律低目标完成'), findsOneWidget);
    await tester.tap(find.text('一键清空'));
    await tester.pumpAndSettle();
    expect(find.text('法律已看完'), findsOneWidget);
    expect(find.text('数学未看完'), findsOneWidget);
  });

  testWidgets('手机双列封面卡片且大量课程按需构建', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _pump(
      tester,
      FakeBackend()..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          for (var i = 0; i < 300; i++)
            courseSummaryJson(id: 'c$i', title: '课程$i'),
        ],
      ),
    );
    expect(
      tester.getTopLeft(find.text('课程0')).dy,
      tester.getTopLeft(find.text('课程1')).dy,
    );
    expect(
      tester.getTopLeft(find.text('课程2')).dy,
      greaterThan(tester.getTopLeft(find.text('课程0')).dy),
    );
    expect(find.text('课程299'), findsNothing);
    expect(tester.takeException(), isNull);
  });

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
    await tester.tap(find.bySemanticsLabel('展开筛选'));
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
    await tester.scrollUntilVisible(
      find.text('英语基础'),
      180,
      scrollable: find.descendant(
        of: find.byType(CustomScrollView),
        matching: find.byType(Scrollable),
      ),
    );
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
    await tester.drag(
      find.descendant(
        of: find.byType(CustomScrollView),
        matching: find.byType(Scrollable),
      ),
      const Offset(0, -300),
    );
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(find.text('课程库')), header);
    expect(tester.getTopLeft(find.text('一键清空')), filters);
    expect(
      backend.requests
          .where(
            (request) =>
                request.method == 'GET' &&
                request.path == '/api/v1/catalog/courses',
          )
          .length,
      1,
    );
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

Future<void> _pump(
  WidgetTester tester,
  FakeBackend backend, {
  double textScale = 1,
}) async {
  final repository = buildRepository(backend);
  // 大响应在真实异步区完成协议解码；UI 测试注入数据快照，筛选仍执行真实 provider。
  final snapshot = await tester.runAsync(repository.loadCourses);
  await tester.pumpWidget(
    ProviderScope(
      retry: (count, error) => null,
      overrides: [
        shanganRepositoryProvider.overrideWithValue(repository),
        libraryCoursesSnapshotProvider.overrideWith((ref) async => snapshot!),
      ],
      child: MaterialApp(
        theme: ShanganTheme.light(),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
        home: const Scaffold(body: LibraryPage()),
      ),
    ),
  );
  await tester.pumpAndSettle();
}
