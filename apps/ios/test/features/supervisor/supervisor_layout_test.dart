import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/supervisor/presentation/supervisor_shell.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 模拟真实刘海安全区，防止默认测试视口零 inset 掩盖顶部重叠。
void main() {
  testWidgets('学员详情遵循安全区和只读督学权限', (tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    tester.view.padding = const FakeViewPadding(top: 177, bottom: 102);
    addTearDown(tester.view.reset);
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/supervisor/learners',
        json: [learnerOverviewJson(userId: 'u-2', canNag: false)],
      )
      ..on(
        'GET',
        '/api/v1/supervisor/learners/u-2',
        json: {
          'userId': 'u-2',
          'username': 'lisi',
          'displayName': '李四',
          'presence': {'state': 'ONLINE', 'idleMinutes': 0},
          'day': dayViewJson(todos: [todoJson(id: 't-1')]),
          'stats': statsJson(),
          'deletions': [],
        },
      );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: const MaterialApp(home: SupervisorLearnerPage(learnerId: 'u-2')),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(find.text('李四')).dy, greaterThanOrEqualTo(59));
    expect(find.bySemanticsLabel('督学 李四'), findsNothing);
    expect(find.text('只读'), findsOneWidget);
    expect(find.byType(Checkbox), findsNothing);
    expect(
      backend.requests.where((request) => request.method != 'GET'),
      isEmpty,
    );
    expect(tester.takeException(), isNull);
  });

  for (final width in [390.0, 320.0]) {
    testWidgets('督学三页避开刘海且详情按钮不竖排：宽 $width', (tester) async {
      tester.view.physicalSize = Size(width * 3, 844 * 3);
      tester.view.devicePixelRatio = 3;
      tester.view.padding = const FakeViewPadding(top: 177, bottom: 102);
      addTearDown(tester.view.reset);
      final backend = FakeBackend()
        ..on('GET', '/api/v1/me', json: meJson())
        ..on(
          'GET',
          '/api/v1/supervisor/learners',
          json: [learnerOverviewJson(userId: 'u-2')],
        )
        ..on('GET', '/api/v1/supervisor/feed', json: [])
        ..on('GET', '/api/v1/supervisor/report', json: []);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            shanganRepositoryProvider.overrideWithValue(
              buildRepository(backend),
            ),
          ],
          child: const MaterialApp(home: SupervisorShell()),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.text('今日学员')).dy, greaterThanOrEqualTo(59));
      final detail = tester.getSize(find.text('查看详情'));
      expect(detail.height, lessThan(30));
      expect(detail.width, greaterThan(50));
      for (final tab in ['提醒', '报告']) {
        await tester.tap(find.text(tab).last);
        await tester.pumpAndSettle();
        final title = tab == '提醒' ? '提醒' : '学员报告';
        expect(
          tester.getTopLeft(find.text(title).first).dy,
          greaterThanOrEqualTo(59),
        );
        expect(tester.takeException(), isNull);
      }
    });
  }
}
