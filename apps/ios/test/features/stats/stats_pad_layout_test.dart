import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/stats/presentation/stats_page.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

void main() {
  testWidgets('Pad 数据页展示计划执行与学习构成', (tester) async {
    tester.view.physicalSize = const Size(1180, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final backend = FakeBackend()
      ..on('GET', '/api/v1/todos', json: dayViewJson(date: '2026-09-10'))
      ..on(
        'GET',
        '/api/v1/stats',
        json: statsJson(
          range: 'WEEK',
          start: '2026-09-07',
          end: '2026-09-13',
          totalTodos: 12,
          doneTodos: 8,
          watchedMs: 4800000,
          focusedMs: 3000000,
          days: [
            {
              'date': '2026-09-10',
              'total': 6,
              'done': 3,
              'backfilled': 0,
              'watchedMs': 4800000,
              'focusedMs': 3000000,
            },
          ],
        ),
      );

    final container = ProviderContainer(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
    );
    addTearDown(container.dispose);
    container.read(statsRangeProvider.notifier).select(HomeRange.week);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: ShanganTheme.light(),
          home: const Scaffold(body: StatsPage()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('计划执行'), findsOneWidget);
    expect(find.text('学习构成'), findsOneWidget);
    expect(find.text('课程观看'), findsWidgets);
    expect(find.text('专注计时'), findsWidgets);
    expect(tester.takeException(), isNull);
  });
}
