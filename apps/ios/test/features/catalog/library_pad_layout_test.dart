import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/catalog/presentation/library_page.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

void main() {
  testWidgets('Pad 课程墙点选后以覆盖抽屉展示课时，课程列表仍留在原位', (tester) async {
    tester.view.physicalSize = const Size(1180, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          courseSummaryJson(
            id: 'a',
            title: '行政法专题',
            people: ['张老师'],
            resourceCount: 1,
          ),
        ],
      )
      ..on(
        'GET',
        '/api/v1/catalog/facets',
        json: {
          'genres': [
            {'value': '法律', 'courseCount': 1},
          ],
          'people': [
            {'value': '张老师', 'courseCount': 1},
          ],
          'tags': <Object>[],
          'years': <Object>[],
        },
      )
      ..on(
        'GET',
        '/api/v1/catalog/courses/a',
        json: courseDetailJson(
          id: 'a',
          title: '行政法专题',
          resources: [courseResourceJson(id: 'r-1', title: '第 01 讲 · 行政法基础')],
        ),
      );
    final repository = buildRepository(backend);
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
          home: const Scaffold(body: LibraryPage()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('共 1 门课程'), findsOneWidget);
    expect(find.byKey(const ValueKey('library-lesson-drawer')), findsNothing);
    expect(find.text('请选择一个课程查看详情'), findsNothing);

    await tester.tap(find.text('行政法专题').first);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('library-lesson-drawer')), findsOneWidget);
    expect(find.byKey(const ValueKey('library-drawer-scrim')), findsOneWidget);
    expect(find.byKey(const ValueKey('course-detail-title')), findsOneWidget);
    expect(find.text('第 01 讲 · 行政法基础'), findsOneWidget);
    expect(find.text('共 1 门课程'), findsOneWidget);
    expect(find.bySemanticsLabel('返回'), findsNothing);
    expect(find.bySemanticsLabel('收起课程详情'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.bySemanticsLabel('收起课程详情'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('library-lesson-drawer')), findsNothing);
    expect(find.text('共 1 门课程'), findsOneWidget);
  });
}
