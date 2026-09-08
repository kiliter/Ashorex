import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/pending_summary_page.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 原型 1-8 未完成汇总的编辑态：默认批量清理作用于全部筛选结果，
/// 进入编辑态后只作用于勾选项，未勾选时必须禁用。
void main() {
  testWidgets('默认态保留批量清理并移除顺延', (tester) async {
    await _pump(tester, _backend());

    expect(find.text('未完成汇总'), findsOneWidget);
    expect(find.text('全部顺延今天'), findsNothing);
    expect(find.text('批量清理'), findsOneWidget);
  });

  testWidgets('进入编辑态后按钮换成计数文案，未勾选时禁用', (tester) async {
    await _pump(tester, _backend());

    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();

    expect(find.text('已选 0 项'), findsOneWidget);
    expect(find.text('完成'), findsOneWidget);
    expect(find.text('顺延 0 项到今天'), findsNothing);
    expect(_button(tester, '删除 0 项').onPressed, isNull);
  });

  testWidgets('下架课时仍可清理，历史任务不再提供批量顺延', (tester) async {
    await _pump(tester, _backend(courseAvailable: false));
    expect(find.text('批量清理'), findsOneWidget);
    expect(find.textContaining('顺延'), findsNothing);
  });

  testWidgets('退出编辑态会清空已勾选项', (tester) async {
    await _pump(tester, _backend());

    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('行政法第 1 讲'));
    await tester.pumpAndSettle();
    expect(find.text('已选 1 项'), findsOneWidget);

    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
    expect(find.text('未完成汇总'), findsOneWidget);

    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();
    expect(find.text('已选 0 项'), findsOneWidget);
  });
}

ButtonStyleButton _button(WidgetTester tester, String label) {
  return tester.widget<ButtonStyleButton>(
    find
        .ancestor(of: find.text(label), matching: find.byType(OutlinedButton))
        .first,
  );
}

FakeBackend _backend({bool courseAvailable = true}) {
  return FakeBackend()
    ..on('GET', '/api/v1/todos?view=DAY', json: dayViewJson(date: '2030-01-02'))
    ..on('GET', '/api/v1/me', json: meJson())
    ..on(
      'GET',
      '/api/v1/todos/pending-summary',
      json: {
        'total': 2,
        'countByType': {'COURSE': 1, 'TASK': 1},
        'items': [
          {
            'todo': todoJson(
              id: 't-1',
              title: '行政法第 1 讲',
              localDate: '2026-09-06',
            )..['resourceAvailable'] = courseAvailable,
            'overdueDays': 1,
            'bucket': 'ONE_DAY',
          },
          {
            'todo': todoJson(
              id: 't-2',
              title: '背 30 个科目',
              todoType: 'TASK',
              localDate: '2026-09-01',
            ),
            'overdueDays': 6,
            'bucket': 'THREE_DAYS_OR_MORE',
          },
        ],
      },
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
      child: const MaterialApp(home: PendingSummaryPage()),
    ),
  );
  await tester.pumpAndSettle();
}
