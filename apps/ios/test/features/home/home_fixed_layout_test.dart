import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/home_page.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 小屏与多目标不应挤掉待办；滚动待办不能带走日期和汇总。
void main() {
  for (final size in [const Size(844, 390), const Size(390, 667)]) {
    testWidgets('固定区与日期选择在 $size 可用', (tester) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final backend = FakeBackend()
        ..on(
          'GET',
          '/api/v1/exam-goals',
          json: [for (var i = 0; i < 9; i++) goalJson(id: 'g-$i')],
        )
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
              for (var i = 0; i < 15; i++) todoJson(id: 't-$i', title: '课程 $i'),
            ],
          ),
        );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            shanganRepositoryProvider.overrideWithValue(
              buildRepository(backend),
            ),
          ],
          child: const MaterialApp(home: Scaffold(body: HomePage())),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      final titlePosition = tester.getTopLeft(find.text('我的目标 · 9 个'));
      final totalPosition = tester.getTopLeft(find.text('今日完成'));
      await tester.drag(find.byType(ListView).first, const Offset(0, -220));
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.text('我的目标 · 9 个')), titlePosition);
      expect(tester.getTopLeft(find.text('今日完成')), totalPosition);
      await tester.tap(find.bySemanticsLabel('切换视图与日期'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('快捷跳转'), findsOneWidget);
      await tester.tap(find.text('昨天'));
      await tester.pumpAndSettle();
      expect(find.text('快捷跳转'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }
}
