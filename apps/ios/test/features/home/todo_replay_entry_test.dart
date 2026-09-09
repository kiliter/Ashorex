import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/features/home/presentation/todo_row.dart';

import '../../support/fixtures.dart';

/// 已完成课程同时保留回放和详情，回放带原日期且不触发创建请求。
void main() {
  testWidgets('已完成复习行显示标记和回放入口，导航携带原待办', (tester) async {
    final todo = TodoItem.fromJson({
      ...todoJson(id: 'done-video', status: 'DONE', resourceId: 'r-1'),
      'review': true,
    });
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
      ProviderScope(child: MaterialApp.router(routerConfig: router)),
    );
    expect(find.text('复习'), findsOneWidget);
    expect(find.bySemanticsLabel('查看 课时'), findsOneWidget);
    await tester.tap(find.byTooltip('回放'));
    await tester.pumpAndSettle();
    expect(find.text('done-video / 2026-09-07'), findsOneWidget);
  });
}
