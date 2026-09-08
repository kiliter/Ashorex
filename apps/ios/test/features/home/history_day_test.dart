import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/home_page.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 历史日：未完成项只能顺延、补记完成或删除，不能直接勾选完成；
/// 全部完成的历史日切换成只读回顾态，且历史日不提供批量编辑入口。
void main() {
  testWidgets('历史日未完成项提供顺延、补记与删除三个动作', (tester) async {
    await _pump(
      tester,
      _backend(
        todos: [
          todoJson(
            id: 't-1',
            title: '行政法第 1 讲',
            localDate: '2026-09-03',
            targetProgressPermille: 300,
          ),
        ],
      ),
    );

    expect(find.text('历史日期 · 只能补记或顺延'), findsOneWidget);
    // 「未完成」同时出现在指标格与分组标题上，因此断言至少存在。
    expect(find.text('未完成'), findsWidgets);
    expect(find.text('顺延今天'), findsOneWidget);
    expect(find.text('补记完成'), findsOneWidget);
    expect(find.text('删除'), findsOneWidget);
  });

  testWidgets('顺延今天把目标日期设为今天并发给服务端', (tester) async {
    final backend = _backend(
      todos: [todoJson(id: 't-1', localDate: '2026-09-03')],
    )..on('POST', '/api/v1/todos/t-1/defer');
    await _pump(tester, backend);

    await tester.tap(find.text('顺延今天'));
    await tester.pumpAndSettle();

    expect(
      backend.lastRequest('POST', '/api/v1/todos/t-1/defer').json['targetDate'],
      '2030-01-02',
    );
  });

  testWidgets('下架历史课时隐藏顺延，仍允许补记与删除', (tester) async {
    await _pump(
      tester,
      _backend(
        todos: [
          todoJson(id: 't-1', localDate: '2026-09-03')
            ..['resourceAvailable'] = false,
        ],
      ),
    );
    expect(find.text('顺延今天'), findsNothing);
    expect(find.text('补记完成'), findsOneWidget);
    expect(find.text('删除'), findsOneWidget);
  });

  testWidgets('补记完成会打开备注必填的完成面板', (tester) async {
    await _pump(
      tester,
      _backend(
        todos: [todoJson(id: 't-1', localDate: '2026-09-03')],
      ),
    );

    await tester.tap(find.text('补记完成'));
    await tester.pumpAndSettle();

    expect(find.textContaining('补记完成 ·'), findsOneWidget);
    expect(find.text('补记必须说明情况'), findsOneWidget);
  });

  testWidgets('历史日全部完成时切换成只读时间轴回顾态', (tester) async {
    await _pump(
      tester,
      _backend(
        todos: [
          todoJson(id: 't-1', localDate: '2026-09-03', status: 'DONE'),
          todoJson(id: 't-2', localDate: '2026-09-03', status: 'DONE'),
        ],
      ),
    );

    expect(find.text('当日时间轴'), findsOneWidget);
    expect(find.textContaining('全部完成'), findsOneWidget);
    expect(find.text('顺延今天'), findsNothing);
    expect(find.text('历史日期 · 只能补记或顺延'), findsNothing);
  });

  testWidgets('历史日不提供批量编辑入口', (tester) async {
    await _pump(
      tester,
      _backend(
        todos: [todoJson(id: 't-1', localDate: '2026-09-03')],
      ),
    );

    expect(find.text('编辑'), findsNothing);
  });

  testWidgets('历史日展示当日删除条数，留下台账痕迹', (tester) async {
    await _pump(
      tester,
      _backend(
        todos: [todoJson(id: 't-1', localDate: '2026-09-03')],
        deletionCount: 2,
      ),
    );

    expect(find.text('当日删除 2 项，已记入台账'), findsOneWidget);
  });
}

FakeBackend _backend({
  required List<Map<String, Object?>> todos,
  int deletionCount = 0,
}) {
  return FakeBackend()
    ..on('GET', '/api/v1/todos?view=DAY', json: dayViewJson(date: '2030-01-02'))
    ..on('GET', '/api/v1/exam-goals', json: const [])
    ..on('GET', '/api/v1/nags/pending')
    ..on('GET', '/api/v1/me', json: meJson())
    ..on(
      'GET',
      '/api/v1/todos/pending-summary',
      json: {
        'total': 0,
        'countByType': <String, Object?>{},
        'items': <Object?>[],
      },
    )
    ..on(
      'GET',
      '/api/v1/todos?view=DAY&date=2026-09-03',
      json: dayViewJson(
        date: '2026-09-03',
        today: false,
        history: true,
        todos: todos,
        deletionCount: deletionCount,
      ),
    );
}

Future<void> _pump(WidgetTester tester, FakeBackend backend) async {
  final container = ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
  addTearDown(container.dispose);
  container
      .read(homeSelectionProvider.notifier)
      .selectDate(DateTime(2026, 9, 3));
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(home: Scaffold(body: HomePage())),
    ),
  );
  await tester.pumpAndSettle();
}
