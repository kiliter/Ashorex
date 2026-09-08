import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/supervisor/presentation/supervisor_shell.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 原型 9-6 学员报告：学员对比 + 完成率最低学员的每日时长 + 课程分布。
/// 每日时长直接用 `/supervisor/report` 已返回的 `stats.days`，不额外发请求。
void main() {
  testWidgets('报告中的一键督学遵循 canNag 权限，区间切换调用真实仓库参数', (tester) async {
    final backend = _backend(canNag: false);
    await _pump(tester, backend);
    await tester.tap(find.text('报告'));
    await tester.pumpAndSettle();
    expect(find.text('一键督学'), findsNothing);
    expect(
      backend.lastRequest('GET', '/api/v1/supervisor/report').path,
      endsWith('range=WEEK'),
    );
    await tester.tap(find.text('月').first);
    await tester.pumpAndSettle();
    expect(
      backend.lastRequest('GET', '/api/v1/supervisor/report').path,
      endsWith('range=MONTH'),
    );
    await tester.tap(find.text('日').first);
    await tester.pumpAndSettle();
    expect(
      backend.lastRequest('GET', '/api/v1/supervisor/report').path,
      endsWith('range=DAY'),
    );
  });

  testWidgets('报告页给完成率最低的学员单独画每日时长', (tester) async {
    final backend = _backend();
    await _pump(tester, backend);
    await tester.tap(find.text('报告'));
    await tester.pumpAndSettle();

    expect(find.text('学员对比'), findsOneWidget);
    // 李四完成 1/4，王五完成 3/4，应该挑李四。
    expect(find.text('李四 · 每日时长'), findsOneWidget);
    expect(find.text('王五 · 每日时长'), findsNothing);
    expect(find.byType(ShanganBarChart), findsOneWidget);
    expect(find.text('查看明细'), findsOneWidget);
    expect(find.text('课程分布 · 全部学员'), findsOneWidget);

    // 图表来自已有响应，不产生额外的统计请求。
    expect(
      backend.requests.where((item) => item.path.startsWith('/api/v1/stats')),
      isEmpty,
    );
  });

  testWidgets('没有按天数据时不渲染每日时长区块', (tester) async {
    await _pump(tester, _backend(withDays: false));
    await tester.tap(find.text('报告'));
    await tester.pumpAndSettle();

    expect(find.text('学员对比'), findsOneWidget);
    expect(find.textContaining('每日时长'), findsNothing);
    expect(find.byType(ShanganBarChart), findsNothing);
  });
}

FakeBackend _backend({bool withDays = true, bool canNag = true}) {
  List<Map<String, Object?>> days(int done) => withDays
      ? [
          daySummaryJson(date: '2026-09-01', total: 2, done: done),
          daySummaryJson(date: '2026-09-02', total: 2, done: 0),
        ]
      : const [];
  return FakeBackend()
    ..on('GET', '/api/v1/me', json: meJson())
    ..on(
      'GET',
      '/api/v1/supervisor/learners',
      json: [learnerOverviewJson(userId: 'u-2', canNag: canNag)],
    )
    ..on('GET', '/api/v1/supervisor/feed', json: const [])
    ..on(
      'GET',
      '/api/v1/supervisor/report',
      json: [
        {
          'userId': 'u-2',
          'username': 'lisi',
          'displayName': '李四',
          'stats': statsJson(
            range: 'WEEK',
            totalTodos: 4,
            doneTodos: 1,
            days: days(1),
          ),
          'nagCount': 2,
          'nagRespondedCount': 1,
        },
        {
          'userId': 'u-3',
          'username': 'wangwu',
          'displayName': '王五',
          'stats': statsJson(
            range: 'WEEK',
            totalTodos: 4,
            doneTodos: 3,
            days: days(2),
          ),
          'nagCount': 0,
          'nagRespondedCount': 0,
        },
      ],
    );
}

Future<void> _pump(WidgetTester tester, FakeBackend backend) async {
  tester.view.physicalSize = const Size(1170, 2532);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
      child: const MaterialApp(home: SupervisorShell()),
    ),
  );
  await tester.pumpAndSettle();
}
