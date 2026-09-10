import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/home_page.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

void main() {
  testWidgets('首页全部/未完成/已完成只过滤当前日列表', (tester) async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/exam-goals', json: <Object>[])
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
        '/api/v1/todos',
        json: dayViewJson(
          todos: [
            todoJson(id: 'pending', title: '未完成课程'),
            todoJson(id: 'done', title: '已完成课程', status: 'DONE'),
          ],
        ),
      );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: const MaterialApp(home: Scaffold(body: HomePage())),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('home-filter-all')), findsOneWidget);
    expect(find.byKey(const ValueKey('home-filter-pending')), findsOneWidget);
    expect(find.byKey(const ValueKey('home-filter-done')), findsOneWidget);
    expect(find.text('未完成课程'), findsOneWidget);
    expect(find.text('已完成课程'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('home-filter-pending')));
    await tester.pump();
    expect(find.text('未完成课程'), findsOneWidget);
    expect(find.text('已完成课程'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('home-filter-done')));
    await tester.pump();
    expect(find.text('未完成课程'), findsNothing);
    expect(find.text('已完成课程'), findsOneWidget);
  });
}
