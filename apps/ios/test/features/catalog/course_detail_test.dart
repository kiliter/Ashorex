import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/catalog/presentation/library_page.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 课程详情：课时按 `sortIndex` 呈现，进度与「以前看过」都由服务端给出，
/// 时长缺失的课时标记为不可度量，不能参与目标进度设定。
void main() {
  testWidgets('长课时列表按需构建且滚动不移动课程头部和操作', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _pumpDetail(tester);
    final title = tester.getTopLeft(
      find.byKey(const ValueKey('course-detail-title')),
    );
    final action = tester.getTopLeft(find.text('批量加入'));
    expect(find.text('课时999'), findsNothing);
    await tester.drag(
      find.byKey(const ValueKey('course-lessons')),
      const Offset(0, -500),
    );
    await tester.pumpAndSettle();
    expect(
      tester.getTopLeft(find.byKey(const ValueKey('course-detail-title'))),
      title,
    );
    expect(tester.getTopLeft(find.text('批量加入')), action);
    expect(find.text('课时0').hitTestable(), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('课程详情横屏键盘下可搜索且课时区域仍可操作', (tester) async {
    tester.view.physicalSize = const Size(844, 390);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    await _pumpDetail(tester);
    await tester.tap(find.bySemanticsLabel('搜索课时'));
    await tester.pumpAndSettle();
    tester.view.viewInsets = const FakeViewPadding(bottom: 220);
    await tester.enterText(find.byType(TextField), '课时99');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(
      tester.getSize(find.byKey(const ValueKey('course-lessons'))).height,
      greaterThan(0),
    );
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('course-lessons')),
        matching: find.text('课时99'),
      ),
      findsOneWidget,
    );
    await tester.enterText(find.byType(TextField), '无此课时');
    await tester.pumpAndSettle();
    expect(find.text('没有符合条件的课时'), findsOneWidget);
  });

  testWidgets('课程详情大字体长名称不挤出课时区域', (tester) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _pumpDetail(tester, textScale: 2);
    expect(tester.takeException(), isNull);
    expect(
      tester.getSize(find.byKey(const ValueKey('course-lessons'))).height,
      greaterThan(100),
    );
  });

  test('课时列表按服务端顺序解析，并带出已看进度', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-1',
        json: courseDetailJson(
          id: 'c-1',
          resources: [
            courseResourceJson(id: 'r-1', title: '第 01 讲 总论', sortIndex: 1),
            courseResourceJson(
              id: 'r-2',
              title: '第 02 讲 分论',
              sortIndex: 2,
              maxPositionMs: 900000,
              watchedMs: 900000,
              progressPermille: 500,
              completedBefore: true,
            ),
          ],
        ),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(courseDetailProvider('c-1').future);

    expect(detail.summary.id, 'c-1');
    expect(detail.resources.map((resource) => resource.title), [
      '第 01 讲 总论',
      '第 02 讲 分论',
    ]);
    expect(detail.resources.last.progressPermille, 500);
    expect(detail.resources.last.completedBefore, isTrue);
    expect(detail.resources.first.completedBefore, isFalse);
  });

  test('时长缺失的课时不可度量', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-1',
        json: courseDetailJson(
          id: 'c-1',
          resources: [
            courseResourceJson(id: 'r-1', durationMs: null, measurable: false),
          ],
        ),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(courseDetailProvider('c-1').future);

    expect(detail.resources.single.durationMs, isNull);
    expect(detail.resources.single.measurable, isFalse);
  });

  test('元数据只读展示，全部来自 Emby 投影', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-1',
        json: {
          'summary': courseSummaryJson(id: 'c-1', productionYear: 2025),
          'genres': ['法律', '公考'],
          'tags': ['重点'],
          'people': [
            {'name': '袁东', 'role': '讲师'},
          ],
          'resources': const [],
        },
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(courseDetailProvider('c-1').future);

    expect(detail.genres, ['法律', '公考']);
    expect(detail.tags, ['重点']);
    expect(detail.people, ['袁东']);
    expect(detail.summary.productionYear, 2025);
    expect(detail.resources, isEmpty);
  });

  test('课程不存在时抛出可识别的接口异常，不静默返回空详情', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-missing',
        status: 404,
        errorCode: 'COURSE_NOT_FOUND',
      );
    final repository = buildRepository(backend);

    ApiException? captured;
    try {
      await repository.loadCourse('c-missing');
    } on ApiException catch (exception) {
      captured = exception;
    }

    expect(captured, isNotNull);
    expect(captured!.statusCode, 404);
    expect(captured.errorCode, 'COURSE_NOT_FOUND');
  });

  test('资源类型缺失时按视频兜底，材料需显式声明', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-1',
        json: {
          'summary': courseSummaryJson(id: 'c-1'),
          'genres': const [],
          'tags': const [],
          'people': const [],
          'resources': [
            {
              'id': 'r-1',
              'title': '无类型资源',
              'sortIndex': 1,
              'durationMs': 60000,
              'measurable': true,
            },
            {
              'id': 'r-2',
              'resourceType': 'DOCUMENT',
              'title': '讲义',
              'sortIndex': 2,
              'pageCount': 30,
              'measurable': true,
            },
          ],
        },
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(courseDetailProvider('c-1').future);

    expect(detail.resources.first.resourceType, ResourceType.video);
    expect(detail.resources.last.resourceType, ResourceType.document);
    expect(detail.resources.last.pageCount, 30);
  });
}

ProviderContainer _container(FakeBackend backend) {
  return ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
}

/// 使用真实主题验证固定区和课时滚动边界，后端仅提供协议数据。
Future<void> _pumpDetail(WidgetTester tester, {double textScale = 1}) async {
  final backend = FakeBackend()
    ..on(
      'GET',
      '/api/v1/catalog/courses/c-1',
      json: courseDetailJson(
        id: 'c-1',
        resources: [
          for (var i = 0; i < 1000; i++)
            courseResourceJson(id: 'r$i', title: '课时$i', sortIndex: i),
        ],
      ),
    );
  final container = _container(backend);
  addTearDown(container.dispose);
  // 保留真实解析链路，但在真实异步区等待大响应的 isolate 解码完成。
  await tester.runAsync(
    () => container.read(courseDetailProvider('c-1').future),
  );
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        theme: ShanganTheme.light(),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
        home: const CourseDetailPage(courseId: 'c-1'),
      ),
    ),
  );
  await tester.pumpAndSettle();
}
