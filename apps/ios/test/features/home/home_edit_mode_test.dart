import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/home_page.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 首页编辑态：批量动作在未选中时必须禁用，批量删除仍要走删除原因弹窗，
/// 拖拽排序只提交顺序，不改变任何完成状态。
void main() {
  testWidgets('进入编辑态后页头换成已选计数，退出后恢复', (tester) async {
    await _pump(tester, _backend());

    expect(find.text('编辑今日'), findsNothing);
    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();

    expect(find.text('编辑今日'), findsOneWidget);
    expect(find.text('已选 0 项'), findsOneWidget);
    expect(find.textContaining('删除任意待办（含批量）都必须填写删除说明'), findsOneWidget);

    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
    expect(find.text('编辑今日'), findsNothing);
  });

  testWidgets('未选中任何项时批量动作禁用', (tester) async {
    await _pump(tester, _backend());
    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();

    expect(_button(tester, '移到明天').onPressed, isNull);
    expect(_button(tester, '删除 0 项').onPressed, isNull);
  });

  testWidgets('勾选后批量顺延把全部 ID 与目标日期发给服务端', (tester) async {
    final backend = _backend()..on('POST', '/api/v1/todos/batch-defer');
    await _pump(tester, backend);
    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('行政法第 1 讲'));
    await tester.pumpAndSettle();
    expect(find.text('已选 1 项'), findsOneWidget);

    await tester.tap(find.text('移到明天'));
    await tester.pumpAndSettle();

    final body = backend.lastRequest('POST', '/api/v1/todos/batch-defer').json;
    expect(body['todoIds'], ['t-1']);
    expect(body['targetDate'], '2026-09-08');
  });

  testWidgets('批量删除必须先经过删除原因弹窗', (tester) async {
    final backend = _backend()..on('POST', '/api/v1/todos/batch-delete');
    await _pump(tester, backend);
    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('行政法第 1 讲'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除 1 项'));
    await tester.pumpAndSettle();

    // 没填原因前不允许提交，也没有任何删除请求发出。
    expect(find.text('删除原因（必填）'), findsOneWidget);
    expect(backend.callCount('POST', '/api/v1/todos/batch-delete'), 0);
  });

  testWidgets('编辑态不展示目标看板与催办条，避免误触', (tester) async {
    await _pump(tester, _backend());
    expect(find.text('我的目标 · 1 个'), findsOneWidget);

    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();

    expect(find.text('我的目标 · 1 个'), findsNothing);
  });
}

ButtonStyleButton _button(WidgetTester tester, String label) {
  return tester.widget<ButtonStyleButton>(
    find
        .ancestor(of: find.text(label), matching: find.byType(OutlinedButton))
        .first,
  );
}

FakeBackend _backend() {
  return FakeBackend()
    ..on('GET', '/api/v1/exam-goals', json: [goalJson(id: 'g-1')])
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
          todoJson(
            id: 't-1',
            title: '行政法第 1 讲',
            targetProgressPermille: 300,
            resourceId: 'r-1',
            resourceDurationMs: 1800000,
          ),
          todoJson(
            id: 't-2',
            title: '背诵 30 分钟',
            todoType: 'FOCUS',
            plannedSeconds: 1800,
          ),
        ],
      ),
    );
}

Future<void> _pump(WidgetTester tester, FakeBackend backend) async {
  // 本文件断言手机端目标看板文案，必须固定为紧凑视口，避免默认 800×600 落入 Pad 布局。
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
      child: const MaterialApp(home: Scaffold(body: HomePage())),
    ),
  );
  await tester.pumpAndSettle();
}
