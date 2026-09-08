import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/stats/presentation/stats_page.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 月度热力图：分档只依据服务端给出的时长与完成数；
/// 「有待办零完成」单独用红底 + 描边表达，重要状态不能只靠颜色，
/// 因此每格必须带可读的语义标签。
void main() {
  testWidgets('每个热力格都带日期、时长与完成数的语义标签', (tester) async {
    await _pump(
      tester,
      days: [
        _day('2026-09-01', total: 3, done: 3, watchedMs: 5400000),
        _day('2026-09-02', total: 2, done: 0),
      ],
    );

    expect(find.bySemanticsLabel('9 月 1 日 · 1:30 · 完成 3/3'), findsOneWidget);
    expect(find.bySemanticsLabel('9 月 2 日 · 0 分 · 完成 0/2'), findsOneWidget);
  });

  testWidgets('有待办零完成的日子与无待办的日子语义可区分', (tester) async {
    await _pump(
      tester,
      days: [
        _day('2026-09-01', total: 2, done: 0),
        _day('2026-09-02', total: 0, done: 0),
      ],
    );

    // 零完成：完成 0/2；无待办：完成 0/0，两者不会被渲染成同一含义。
    expect(find.bySemanticsLabel('9 月 1 日 · 0 分 · 完成 0/2'), findsOneWidget);
    expect(find.bySemanticsLabel('9 月 2 日 · 0 分 · 完成 0/0'), findsOneWidget);
  });

  testWidgets('热力图图例说明四档时长口径', (tester) async {
    await _pump(
      tester,
      days: [_day('2026-09-01', total: 1, done: 1, watchedMs: 3600000)],
    );

    expect(find.text('学习热力'), findsOneWidget);
    expect(find.text('1 小时内'), findsOneWidget);
  });

  testWidgets('没有日数据时不渲染空网格', (tester) async {
    await _pump(tester, days: const []);

    expect(find.byType(GridView), findsNothing);
  });

  testWidgets('时长分档按观看与专注之和计算', (tester) async {
    await _pump(
      tester,
      days: [
        // 观看 1 小时 + 专注 1 小时 = 2 小时，落在第三档而不是第二档。
        _day(
          '2026-09-01',
          total: 2,
          done: 2,
          watchedMs: 3600000,
          focusedMs: 3600000,
        ),
      ],
    );

    expect(find.bySemanticsLabel('9 月 1 日 · 2:00 · 完成 2/2'), findsOneWidget);
  });
}

Map<String, Object?> _day(
  String date, {
  int total = 0,
  int done = 0,
  int watchedMs = 0,
  int focusedMs = 0,
}) {
  return {
    'date': date,
    'total': total,
    'done': done,
    'backfilled': 0,
    'watchedMs': watchedMs,
    'focusedMs': focusedMs,
  };
}

Future<void> _pump(
  WidgetTester tester, {
  required List<Map<String, Object?>> days,
}) async {
  final backend = FakeBackend()
    ..on(
      'GET',
      '/api/v1/stats',
      json: statsJson(
        range: 'MONTH',
        start: '2026-09-01',
        end: '2026-09-30',
        days: days,
      ),
    );
  tester.view.physicalSize = const Size(1170, 2532);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  final container = ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
  addTearDown(container.dispose);
  // 热力图只在月视图渲染（原型 5-3）。
  container.read(statsRangeProvider.notifier).select(HomeRange.month);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(home: Scaffold(body: StatsPage())),
    ),
  );
  await tester.pumpAndSettle();
}
