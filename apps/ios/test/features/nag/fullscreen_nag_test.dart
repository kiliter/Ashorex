import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/nag/presentation/fullscreen_nag_page.dart';

import '../../support/fake_backend.dart';

/// 全屏催办是 AGENTS.md 与 ADR-0026 共同点名的强约束页面：
/// 不可返回、不可点外部关闭、必须选原因并填够字数才能提交。
void main() {
  // 横屏和键盘占用高度时，原因与提交入口仍应可滚动到达。
  testWidgets('横屏弹出键盘后可填写并提交催办', (tester) async {
    tester.view.physicalSize = const Size(844, 390);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    final backend = FakeBackend()..on('POST', '/api/v1/nags/n-1/respond');
    await _pump(tester, backend, nag: _nag(), pushed: true);
    expect(tester.takeException(), isNull);
    await tester.ensureVisible(find.text('突发事情'));
    await tester.tap(find.text('突发事情'));
    tester.view.viewInsets = const FakeViewPadding(bottom: 220);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.ensureVisible(find.byType(TextField));
    await tester.enterText(find.byType(TextField), '家里有急事刚处理完');
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('提交原因并继续'));
    await tester.tap(find.text('提交原因并继续'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(
      backend
          .lastRequest('POST', '/api/v1/nags/n-1/respond')
          .json['reasonText'],
      '家里有急事刚处理完',
    );
    expect(find.byType(FullscreenNagPage), findsNothing);
  });

  testWidgets('心跳与 SSE 同时触发也只展示一个全屏页', (tester) async {
    late BuildContext launchContext;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(
            buildRepository(FakeBackend()),
          ),
        ],
        child: MaterialApp(
          home: Builder(
            builder: (context) {
              launchContext = context;
              return const Scaffold();
            },
          ),
        ),
      ),
    );
    final first = FullscreenNagPage.show(
      launchContext,
      nag: _nag(),
      minReasonLength: 5,
    );
    final second = FullscreenNagPage.show(
      launchContext,
      nag: _nag(),
      minReasonLength: 5,
    );
    expect(identical(first, second), isTrue);
    await tester.pumpAndSettle();
    expect(find.byType(FullscreenNagPage), findsOneWidget);
  });

  testWidgets('未选原因或字数不足时提交按钮禁用', (tester) async {
    await _pump(tester, FakeBackend(), nag: _nag());

    expect(find.text('你已经 95 分钟没动了'), findsOneWidget);
    expect(_submitButton(tester).onPressed, isNull);

    await tester.enterText(find.byType(TextField), '临时开会来不及');
    await tester.pump();
    expect(_submitButton(tester).onPressed, isNull);

    await tester.tap(find.text('临时加班'));
    await tester.pump();
    expect(_submitButton(tester).onPressed, isNotNull);
  });

  testWidgets('字数不足最少字数时不可提交', (tester) async {
    await _pump(tester, FakeBackend(), nag: _nag(), minReasonLength: 8);

    await tester.tap(find.text('纯粹偷懒'));
    await tester.pump();
    await tester.enterText(find.byType(TextField), '偷懒了');
    await tester.pump();
    expect(_submitButton(tester).onPressed, isNull);
    expect(find.textContaining('至少 8 个字'), findsOneWidget);

    await tester.enterText(find.byType(TextField), '偷懒了现在马上开始学习');
    await tester.pump();
    expect(_submitButton(tester).onPressed, isNotNull);
  });

  testWidgets('系统返回手势不能关闭全屏催办', (tester) async {
    await _pump(tester, FakeBackend(), nag: _nag());

    final popScope = tester
        .widgetList(find.byWidgetPredicate((widget) => widget is PopScope))
        .single;
    expect((popScope as dynamic).canPop, isFalse);

    // 真实触发一次返回：页面必须仍在。
    final result = await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(result, isTrue);
    expect(find.byType(FullscreenNagPage), findsOneWidget);
  });

  testWidgets('提交时把原因标签与说明发给服务端并关闭页面', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/nags/n-1/respond');
    await _pump(tester, backend, nag: _nag(), pushed: true);

    await tester.tap(find.text('突发事情'));
    await tester.pump();
    await tester.enterText(find.byType(TextField), '家里有急事刚处理完');
    await tester.pump();
    await tester.tap(find.text('提交原因并继续'));
    await tester.pumpAndSettle();

    final request = backend.lastRequest('POST', '/api/v1/nags/n-1/respond');
    expect(request.json['reasonTag'], NagReasonTag.emergency.wire);
    expect(request.json['reasonText'], '家里有急事刚处理完');
    expect(find.byType(FullscreenNagPage), findsNothing);
  });

  testWidgets('提交失败时保留页面并展示错误，不放行使用', (tester) async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/nags/n-1/respond',
        status: 409,
        errorCode: 'NAG_ALREADY_RESPONDED',
      );
    await _pump(tester, backend, nag: _nag(), pushed: true);

    await tester.tap(find.text('身体不适'));
    await tester.pump();
    await tester.enterText(find.byType(TextField), '有点发烧在休息');
    await tester.pump();
    await tester.tap(find.text('提交原因并继续'));
    await tester.pumpAndSettle();

    expect(find.textContaining('提交失败'), findsOneWidget);
    expect(find.byType(FullscreenNagPage), findsOneWidget);
  });

  for (final errorCode in const [
    'NAG_EXPIRED',
    'NAG_CANCELLED',
    'NAG_NOT_FOUND',
  ]) {
    testWidgets('终态 $errorCode 关闭旧催办页交给外层重新查询', (tester) async {
      final backend = FakeBackend()
        ..on(
          'POST',
          '/api/v1/nags/n-1/respond',
          status: errorCode == 'NAG_NOT_FOUND' ? 404 : 409,
          errorCode: errorCode,
        );
      await _pump(tester, backend, nag: _nag(), pushed: true);

      await tester.tap(find.text('临时加班'));
      await tester.pump();
      await tester.enterText(find.byType(TextField), '跨天后已经重新安排学习');
      await tester.pump();
      await tester.tap(find.text('提交原因并继续'));
      await tester.pumpAndSettle();

      expect(find.byType(FullscreenNagPage), findsNothing);
    });
  }

  testWidgets('不要求原因的催办可直接提交，标签兜底为 TEMP_BUSY', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/nags/n-1/respond');
    await _pump(tester, backend, nag: _nag(requireReason: false), pushed: true);

    expect(_submitButton(tester).onPressed, isNotNull);
    await tester.tap(find.text('提交原因并继续'));
    await tester.pumpAndSettle();

    expect(
      backend.lastRequest('POST', '/api/v1/nags/n-1/respond').json['reasonTag'],
      NagReasonTag.tempBusy.wire,
    );
  });

  testWidgets('服务端没给文案时展示未完成条数兜底文案', (tester) async {
    await _pump(tester, FakeBackend(), nag: _nag(message: ''));

    expect(find.textContaining('今天还有 4 项没完成'), findsOneWidget);
    expect(find.text('4 项'), findsOneWidget);
  });
}

FilledButton _submitButton(WidgetTester tester) {
  return tester.widget<FilledButton>(
    find.ancestor(
      of: find.textContaining('提交'),
      matching: find.byType(FilledButton),
    ),
  );
}

PendingNag _nag({
  String message = '今天还有 4 项没完成，说明一下情况。',
  bool requireReason = true,
}) {
  return PendingNag(
    id: 'n-1',
    message: message,
    idleMinutes: 95,
    pendingCount: 4,
    requireReason: requireReason,
  );
}

/// [pushed] 为真时通过 `FullscreenNagPage.show` 压栈，便于断言提交后页面确实退出。
Future<void> _pump(
  WidgetTester tester,
  FakeBackend backend, {
  required PendingNag nag,
  int minReasonLength = 5,
  bool pushed = false,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
      child: MaterialApp(
        home: pushed
            ? Scaffold(
                body: Builder(
                  builder: (context) => TextButton(
                    onPressed: () => FullscreenNagPage.show(
                      context,
                      nag: nag,
                      minReasonLength: minReasonLength,
                    ),
                    child: const Text('拉起催办'),
                  ),
                ),
              )
            : FullscreenNagPage(nag: nag, minReasonLength: minReasonLength),
      ),
    ),
  );
  if (pushed) {
    await tester.tap(find.text('拉起催办'));
  }
  await tester.pumpAndSettle();
}
