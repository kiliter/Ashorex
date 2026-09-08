import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/home_page.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 历史执行走普通完成，刷新后留在还债区域，不改变今日计划。
void main() {
  testWidgets('还债完成后保留记录并单列增量', (tester) async {
    tester.view.physicalSize = const Size(390, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final old = todoJson(
      id: 'old',
      title: '昨日事项',
      todoType: 'TASK',
      localDate: '2026-09-06',
      watchedMs: 3600000,
    );
    final initial = dayViewJson()
      ..['repaymentTodos'] = [old]
      ..['repayment'] = {'done': 0, 'watchedMs': 60000, 'focusedMs': 0};
    final backend = FakeBackend()
      ..on('GET', '/api/v1/exam-goals', json: const [])
      ..on('GET', '/api/v1/me', json: meJson())
      ..on('GET', '/api/v1/nags/pending')
      ..on(
        'GET',
        '/api/v1/todos/pending-summary',
        json: {'total': 1, 'items': []},
      )
      ..on('GET', '/api/v1/todos', json: initial)
      ..on('GET', '/api/v1/todos/old/attachments', json: const [])
      ..on('POST', '/api/v1/todos/old/complete');
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: const MaterialApp(home: Scaffold(body: HomePage())),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('今日还债'), findsOneWidget);
    expect(find.text('今日完成 0 项 · 观看 1 分 · 专注 0 分'), findsOneWidget);
    expect(find.text('原计划 2026-09-06'), findsOneWidget);
    await tester.tap(find.bySemanticsLabel('开始 昨日事项'));
    await tester.pumpAndSettle();
    backend.on(
      'GET',
      '/api/v1/todos',
      json: dayViewJson()
        ..['repaymentTodos'] = [
          {...old, 'status': 'DONE'},
        ]
        ..['repayment'] = {'done': 1, 'watchedMs': 60000, 'focusedMs': 0},
    );
    await tester.tap(find.text('保存并完成'));
    await tester.pumpAndSettle();
    expect(
      backend
          .lastRequest('POST', '/api/v1/todos/old/complete')
          .json['backfill'],
      isFalse,
    );
    expect(find.text('原计划 2026-09-06 · 今日已完成'), findsOneWidget);
    expect(find.text('今日完成 1 项 · 观看 1 分 · 专注 0 分'), findsOneWidget);
    expect(backend.callCount('POST', '/api/v1/todos/old/defer'), 0);
    expect(tester.takeException(), isNull);
  });
}
