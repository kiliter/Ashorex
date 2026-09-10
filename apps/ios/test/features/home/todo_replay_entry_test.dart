import 'package:shangan_ios/core/state/shangan_providers.dart';
import '../../support/fake_backend.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/features/home/presentation/todo_row.dart';

import '../../support/fixtures.dart';

/// 已完成课程同时保留回放和详情，回放带原日期且不触发创建请求。
void main() {
  testWidgets('复习必须确认，取消不请求，确认后带原待办进入播放器', (tester) async {
    final todo = TodoItem.fromJson({
      ...todoJson(id: 'done-video', status: 'DONE', resourceId: 'r-1'),
      'review': true,
    });
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/done-video/review',
        json: {'epoch': 1, 'resumePositionMs': 0},
      );
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (context, state) => Scaffold(
            body: TodoRow(todo: todo, onChanged: () async {}),
          ),
        ),
        GoRoute(
          path: '/player/:id',
          builder: (context, state) => Text(
            '${state.pathParameters['id']} / ${state.uri.queryParameters['date']}',
          ),
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    expect(find.text('复习'), findsOneWidget);
    expect(find.bySemanticsLabel('查看 课时'), findsOneWidget);
    await tester.tap(find.byTooltip('复习'));
    await tester.pumpAndSettle();
    expect(find.text('确认重新复习？'), findsOneWidget);
    expect(backend.requests, isEmpty);
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(backend.requests, isEmpty);
    await tester.tap(find.byTooltip('复习'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认复习'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/done-video/review'), 1);
    expect(
      backend
          .lastRequest('POST', '/api/v1/todos/done-video/review')
          .json['expectedPlaybackEpoch'],
      0,
    );
    expect(find.text('done-video / 2026-09-07'), findsOneWidget);
  });
}
