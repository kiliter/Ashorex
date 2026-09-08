import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/supervisor/presentation/supervisor_shell.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 一键督学面板：话术模板可一键填入，渠道自动模式必须按在线状态说明降级路径，
/// 「必须填写原因」默认开启，发送载荷要把三项一起交给服务端。
void main() {
  testWidgets('学员卡展示今日完成与在线状态，可打开督学面板', (tester) async {
    await _pump(tester, _backend());

    expect(find.text('李四'), findsOneWidget);
    await tester.tap(find.text('一键督学').last);
    await tester.pumpAndSettle();

    expect(find.text('督学 李四'), findsOneWidget);
    expect(find.text('话术模板'), findsOneWidget);
    expect(find.text('投递渠道'), findsOneWidget);
  });

  testWidgets('点选话术模板会填入完整文案', (tester) async {
    await _pump(tester, _backend());
    await tester.tap(find.text('一键督学').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('今天一项没动'));
    await tester.pumpAndSettle();

    expect(
      tester.widget<TextField>(find.byType(TextField).last).controller?.text,
      '今天一项没动，先把第一条完成，完成了给我回一句。',
    );
  });

  testWidgets('学员在线时自动模式说明会直接弹全屏催办', (tester) async {
    await _pump(tester, _backend(presenceState: 'ONLINE'));
    await tester.tap(find.text('一键督学').last);
    await tester.pumpAndSettle();

    expect(find.textContaining('学员当前在线，自动模式会直接弹全屏催办'), findsOneWidget);
  });

  testWidgets('学员离线时自动模式说明会降级到 Server 酱', (tester) async {
    await _pump(tester, _backend(presenceState: 'OFFLINE'));
    await tester.tap(find.text('一键督学').last);
    await tester.pumpAndSettle();

    expect(find.textContaining('学员当前离线，自动模式优先走学员 Bark'), findsOneWidget);
  });

  testWidgets('发送督学把文案、渠道与是否必须回应一起提交', (tester) async {
    final backend = _backend()
      ..on('POST', '/api/v1/supervisor/learners/u-2/nag');
    await _pump(tester, backend);
    await tester.tap(find.text('一键督学').last);
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, '今天完成一课');
    await tester.tap(find.text('进度落后了'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Server 酱'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('发送督学'));
    await tester.pumpAndSettle();

    final body = backend
        .lastRequest('POST', '/api/v1/supervisor/learners/u-2/nag')
        .json;
    expect(body['message'], '进度落后了，今晚补一条，别再往后拖。');
    expect(body['title'], '今天完成一课');
    expect(body['channel'], 'SERVERCHAN');
    expect(body['requireReason'], isTrue);
  });

  testWidgets('可关闭「必须填写原因」，但默认是开启的', (tester) async {
    final backend = _backend()
      ..on('POST', '/api/v1/supervisor/learners/u-2/nag');
    await _pump(tester, backend);
    await tester.tap(find.text('一键督学').last);
    await tester.pumpAndSettle();

    expect(find.text('必须填写原因'), findsOneWidget);
    expect(tester.widget<Switch>(find.byType(Switch)).value, isTrue);

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    await tester.tap(find.text('发送督学'));
    await tester.pumpAndSettle();

    expect(
      backend
          .lastRequest('POST', '/api/v1/supervisor/learners/u-2/nag')
          .json['requireReason'],
      isFalse,
    );
  });

  testWidgets('发送失败时保留面板并提示，不误判为已发送', (tester) async {
    final backend = _backend()
      ..on(
        'POST',
        '/api/v1/supervisor/learners/u-2/nag',
        status: 403,
        errorCode: 'SUPERVISION_NOT_ALLOWED',
      );
    await _pump(tester, backend);
    await tester.tap(find.text('一键督学').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('发送督学'));
    await tester.pumpAndSettle();

    expect(find.textContaining('发送失败'), findsOneWidget);
    expect(find.text('发送督学'), findsOneWidget);
  });
}

FakeBackend _backend({String presenceState = 'ONLINE'}) {
  return FakeBackend()
    ..on('GET', '/api/v1/me', json: meJson())
    ..on(
      'GET',
      '/api/v1/supervisor/learners',
      json: [
        learnerOverviewJson(
          userId: 'u-2',
          presenceState: presenceState,
          alerts: const ['ZERO_DONE'],
        ),
      ],
    )
    ..on('GET', '/api/v1/supervisor/feed', json: const [])
    ..on('GET', '/api/v1/supervisor/report', json: const []);
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
