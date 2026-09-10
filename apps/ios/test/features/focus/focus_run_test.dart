import 'package:shangan_ios/core/presence/app_activity.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/device/attachment_picker.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/focus/presentation/focus_run_page.dart';

import '../../support/fake_attachment_picker.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 专注两种终态：倒计时归零自动 finish 判定完成；提前跳过走二次确认后 abandon，
/// 记为未完成但保留已专注时长。状态迁移一律由服务端裁决。
void main() {
  testWidgets('到点补凭证保存后仍调用专注finish而不是通用complete', (tester) async {
    final backend =
        _backend(
            plannedSeconds: 3,
            focusState: 'RUNNING',
            requireEvidence: true,
          )
          ..on(
            'POST',
            '/api/v1/todos/t-1/focus/pause',
            json: _actionJson('pause'),
          )
          ..on('GET', '/api/v1/todos/t-1/attachments', json: [])
          ..on('POST', '/api/v1/todos/t-1/attachments', json: _proof)
          ..on(
            'GET',
            '/api/v1/todos/t-1/attachments/a-1/content',
            bytes: onePixelPng,
          )
          ..on('POST', '/api/v1/todos/t-1/annotate')
          ..on('POST', '/api/v1/todos/t-1/complete')
          ..on(
            'POST',
            '/api/v1/todos/t-1/focus/finish',
            json: _actionJson('finish'),
          );
    await _pump(
      tester,
      backend,
      picker: FakeAttachmentPicker(result: pickedPng()),
    );
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    // 暂停后重新加载的服务端快照，等待凭证期间不继续计时。
    backend.on(
      'GET',
      '/api/v1/todos',
      json: dayViewJson(
        todos: [
          todoJson(
            id: 't-1',
            title: '法条背诵',
            todoType: 'FOCUS',
            plannedSeconds: 3,
            focusState: 'PAUSED',
            focusedMs: 3000,
            status: 'IN_PROGRESS',
            requireEvidence: true,
            attachmentCount: 1,
          ),
        ],
      ),
    );
    backend.on('GET', '/api/v1/todos/t-1/attachments', json: [_proof]);
    await tester.tap(find.text('添加'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('拍照'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '本轮专注收获');
    await tester.ensureVisible(find.text('保存并完成'));
    await tester.tap(find.text('保存并完成'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/complete'), 0);
    expect(
      backend.lastRequest('POST', '/api/v1/todos/t-1/annotate').json['note'],
      '本轮专注收获',
    );
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/finish'), 1);
    expect(
      backend.requests
          .where((r) => r.method == 'POST')
          .map((r) => Uri.parse(r.path).path),
      [
        '/api/v1/todos/t-1/focus/pause',
        '/api/v1/todos/t-1/attachments',
        '/api/v1/todos/t-1/annotate',
        '/api/v1/todos/t-1/focus/finish',
      ],
    );
    await _dispose(tester);
  });

  testWidgets('专注备注入口仅回填且不要求提前上传凭证', (tester) async {
    final backend = _backend(focusState: 'PAUSED', requireEvidence: true)
      ..on('GET', '/api/v1/todos/t-1/attachments', json: [])
      ..on('POST', '/api/v1/todos/t-1/annotate');
    await _pump(tester, backend);
    await tester.ensureVisible(find.text('备注'));
    await tester.tap(find.text('备注'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '计时中的备注');
    await tester.ensureVisible(find.text('保存'));
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/annotate'), 1);
    expect(backend.callCount('POST', '/api/v1/todos/t-1/complete'), 0);
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/finish'), 0);
    expect(find.text('已暂停'), findsOneWidget);
    await _dispose(tester);
  });

  testWidgets('到点缺凭证取消回填保持暂停且不执行完成', (tester) async {
    final backend =
        _backend(
            plannedSeconds: 3,
            focusState: 'RUNNING',
            requireEvidence: true,
          )
          ..on(
            'POST',
            '/api/v1/todos/t-1/focus/pause',
            json: _actionJson('pause'),
          )
          ..on('GET', '/api/v1/todos/t-1/attachments', json: []);
    await _pump(tester, backend);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    backend.on(
      'GET',
      '/api/v1/todos',
      json: dayViewJson(
        todos: [
          todoJson(
            id: 't-1',
            todoType: 'FOCUS',
            plannedSeconds: 3,
            focusedMs: 3000,
            focusState: 'PAUSED',
            status: 'IN_PROGRESS',
            requireEvidence: true,
          ),
        ],
      ),
    );
    final save = tester.widget<FilledButton>(
      find.ancestor(
        of: find.text('保存并完成'),
        matching: find.byType(FilledButton),
      ),
    );
    expect(save.onPressed, isNull);
    await tester.ensureVisible(find.text('稍后再填'));
    await tester.tap(find.text('稍后再填'));
    await tester.pumpAndSettle();
    expect(find.text('已暂停'), findsOneWidget);
    expect(backend.callCount('POST', '/api/v1/todos/t-1/complete'), 0);
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/finish'), 0);
    await _dispose(tester);
  });

  testWidgets('专注进度环与小屏滚动布局', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await _pump(tester, _backend(focusState: 'PAUSED', focusedMs: 22000));
    expect(find.text('已暂停'), findsOneWidget);
    expect(find.text('未完成'), findsOneWidget);
    expect(find.textContaining('%'), findsNothing);
    expect(tester.takeException(), isNull);
    tester.view.physicalSize = const Size(320, 568);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('继续'));
    expect(find.text('继续').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
    await _dispose(tester);
  });

  testWidgets('专注前台运行常亮，后台暂停和离页释放', (tester) async {
    final wake = _Wake();
    final backend = _backend()
      ..on('POST', '/api/v1/todos/t-1/focus/start', json: _actionJson('start'))
      ..on('POST', '/api/v1/todos/t-1/focus/pause', json: _actionJson('pause'));
    await _pump(tester, backend, wake: wake);
    await tester.ensureVisible(find.text('开始专注'));
    await tester.tap(find.text('开始专注'));
    await tester.pumpAndSettle();
    expect(wake.enabled, isTrue);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    expect(wake.enabled, isFalse);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(wake.enabled, isTrue);
    await tester.ensureVisible(find.text('暂停'));
    await tester.tap(find.text('暂停'));
    await tester.pumpAndSettle();
    expect(wake.enabled, isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
    expect(wake.enabled, isFalse);
  });

  testWidgets('未开始时展示开始按钮与目标时长', (tester) async {
    await _pump(tester, _backend());

    expect(find.text('开始专注'), findsOneWidget);
    expect(find.text('法条背诵'), findsOneWidget);
    expect(find.textContaining('目标 25:00'), findsOneWidget);
    expect(find.text('25:00'), findsOneWidget);
  });

  testWidgets('开始专注调用 start，并开始倒计时', (tester) async {
    final backend = _backend()
      ..on('POST', '/api/v1/todos/t-1/focus/start', json: _actionJson('start'));
    await _pump(tester, backend);

    await tester.ensureVisible(find.text('开始专注'));
    await tester.tap(find.text('开始专注'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/start'), 1);
    expect(
      tester.widget<AppActivityScope>(find.byType(AppActivityScope)).activity,
      const AppActivity('FOCUS', 'FOCUS_RUNNING', 't-1'),
    );

    await tester.pump(const Duration(seconds: 3));
    expect(find.text('24:57'), findsOneWidget);
    expect(find.text('暂停'), findsOneWidget);

    // 结束测试前退出页面，避免倒计时定时器残留。
    await _dispose(tester);
  });

  testWidgets('暂停与继续分别调用 pause 与 resume', (tester) async {
    final backend = _backend(focusState: 'RUNNING', focusedMs: 5000)
      ..on('POST', '/api/v1/todos/t-1/focus/pause', json: _actionJson('pause'))
      ..on(
        'POST',
        '/api/v1/todos/t-1/focus/resume',
        json: _actionJson('resume'),
      );
    await _pump(tester, backend);

    await tester.ensureVisible(find.text('暂停'));
    await tester.tap(find.text('暂停'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/pause'), 1);
    expect(
      tester.widget<AppActivityScope>(find.byType(AppActivityScope)).activity,
      const AppActivity('FOCUS', 'FOCUS_PAUSED', 't-1'),
    );

    await tester.ensureVisible(find.text('继续'));
    await tester.tap(find.text('继续'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/resume'), 1);

    await _dispose(tester);
  });

  testWidgets('跳过必须二次确认，并说明已专注时长仍然保留', (tester) async {
    final backend = _backend(focusState: 'RUNNING', focusedMs: 300000)
      ..on(
        'POST',
        '/api/v1/todos/t-1/focus/abandon',
        json: _actionJson('abandon'),
      );
    await _pump(tester, backend);

    await tester.ensureVisible(find.text('跳过'));
    await tester.tap(find.text('跳过'));
    await tester.pumpAndSettle();

    expect(find.text('跳过这次专注？'), findsOneWidget);
    expect(find.text('会记为未完成，但已专注的时长仍然保留并进入统计。'), findsOneWidget);

    // 先选「继续专注」：不得调用 abandon。
    await tester.ensureVisible(find.text('返回专注'));
    await tester.tap(find.text('返回专注'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/abandon'), 0);

    await tester.ensureVisible(find.text('跳过'));
    await tester.tap(find.text('跳过'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('确认跳过'));
    await tester.tap(find.text('确认跳过'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/abandon'), 1);
  });

  testWidgets('倒计时归零自动调用 finish 判定完成', (tester) async {
    final backend = _backend(plannedSeconds: 3, focusState: 'RUNNING')
      ..on('POST', '/api/v1/todos/t-1/focus/pause', json: _actionJson('pause'))
      ..on(
        'POST',
        '/api/v1/todos/t-1/focus/finish',
        json: _actionJson('finish'),
      );
    await _pump(tester, backend);

    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/finish'), 1);
  });

  testWidgets('要求凭证时展示拍照要求与提前回填入口', (tester) async {
    await _pump(tester, _backend(requireEvidence: true));

    expect(find.text('完成后需要'), findsOneWidget);
    expect(find.text('拍照 1 张'), findsOneWidget);
    expect(find.text('提前拍照'), findsOneWidget);
  });

  testWidgets('原型 3-2「提前拍照」直接上传附件，不等专注结束', (tester) async {
    final backend = _backend(requireEvidence: true)
      ..on(
        'POST',
        '/api/v1/todos/t-1/attachments',
        json: const {
          'id': 'a-1',
          'filename': 'proof.png',
          'contentType': 'image/png',
          'sizeBytes': 2048,
          'sortOrder': 0,
          'createdAt': '2026-09-07T01:00:00Z',
          'downloadUrl': '/api/v1/todos/t-1/attachments/a-1/content',
        },
      );
    final picker = FakeAttachmentPicker(result: pickedPng());
    await _pump(tester, backend, picker: picker);

    await tester.ensureVisible(find.text('提前拍照'));
    await tester.tap(find.text('提前拍照'));
    await tester.pumpAndSettle();

    expect(picker.requested, [AttachmentSource.camera]);
    final request = backend.lastRequest(
      'POST',
      '/api/v1/todos/t-1/attachments',
    );
    expect((request.body! as FormData).files.single.key, 'file');
    expect(find.text('凭证已上传'), findsOneWidget);
  });

  testWidgets('提前拍照被服务端拒绝时给出中文错误文案', (tester) async {
    final backend = _backend(requireEvidence: true)
      ..on(
        'POST',
        '/api/v1/todos/t-1/attachments',
        status: 400,
        errorCode: 'ATTACHMENT_TOO_LARGE',
      );
    await _pump(
      tester,
      backend,
      picker: FakeAttachmentPicker(result: pickedPng()),
    );

    await tester.ensureVisible(find.text('提前拍照'));
    await tester.tap(find.text('提前拍照'));
    await tester.pumpAndSettle();

    expect(find.text('单个附件不能超过 10MB'), findsOneWidget);
  });

  testWidgets('提前拍照成功后完成时不再强制弹回填面板', (tester) async {
    final backend =
        _backend(
            plannedSeconds: 3,
            focusState: 'RUNNING',
            requireEvidence: true,
          )
          ..on(
            'POST',
            '/api/v1/todos/t-1/attachments',
            json: const {
              'id': 'a-1',
              'filename': 'proof.png',
              'contentType': 'image/png',
              'sizeBytes': 2048,
              'sortOrder': 0,
              'createdAt': '2026-09-07T01:00:00Z',
              'downloadUrl': '/api/v1/todos/t-1/attachments/a-1/content',
            },
          )
          ..on(
            'POST',
            '/api/v1/todos/t-1/focus/pause',
            json: _actionJson('pause'),
          )
          ..on(
            'POST',
            '/api/v1/todos/t-1/focus/finish',
            json: _actionJson('finish'),
          );
    await _pump(
      tester,
      backend,
      picker: FakeAttachmentPicker(result: pickedPng()),
    );

    await tester.ensureVisible(find.text('提前拍照'));
    await tester.tap(find.text('提前拍照'));
    await tester.pumpAndSettle();

    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(find.text('完成凭证（必填）'), findsNothing);
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/finish'), 1);
  });

  testWidgets('已跳过重入不显示继续和开始，跳过位于页面顶部', (tester) async {
    await _pump(tester, _backend(focusState: 'ABANDONED', focusedMs: 22000));
    expect(find.text('已跳过'), findsOneWidget);
    expect(find.text('继续'), findsNothing);
    expect(find.text('开始专注'), findsNothing);
    expect(find.text('停止'), findsNothing);
    await _dispose(tester);
  });

  testWidgets('停止需确认，再次开始从零计时且保留历史累计', (tester) async {
    final backend = _backend(focusState: 'RUNNING', focusedMs: 300000)
      ..on(
        'POST',
        '/api/v1/todos/t-1/focus/stop',
        json: {..._actionJson('stop'), 'focusedMs': 300000},
      )
      ..on(
        'POST',
        '/api/v1/todos/t-1/focus/start',
        json: {
          ..._actionJson('start'),
          'focusedMs': 300000,
          'focusAttemptBaseMs': 300000,
        },
      );
    await _pump(tester, backend);
    expect(tester.getTopLeft(find.text('跳过')).dy, lessThan(100));
    await tester.ensureVisible(find.text('停止'));
    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/stop'), 0);
    await tester.tap(find.text('确认停止'));
    await tester.pumpAndSettle();
    expect(find.text('已停止'), findsOneWidget);
    expect(backend.callCount('POST', '/api/v1/todos/t-1/focus/stop'), 1);
    await tester.ensureVisible(find.text('再次开始'));
    await tester.tap(find.text('再次开始'));
    await tester.pumpAndSettle();
    expect(find.text('25:00'), findsOneWidget);
    expect(find.textContaining('本次已专注 0:00'), findsOneWidget);
    expect(find.textContaining('%'), findsNothing);
    await _dispose(tester);
  });

  testWidgets('成功提示浮动在右上角并支持关闭', (tester) async {
    final backend = _backend(focusState: 'PAUSED', focusedMs: 3000)
      ..on(
        'POST',
        '/api/v1/todos/t-1/focus/abandon',
        json: _actionJson('abandon'),
      );
    await _pump(tester, backend);
    await tester.tap(find.text('跳过'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认跳过'));
    await tester.pumpAndSettle();
    final notice = find.text('已跳过，专注时长已保留');
    expect(notice, findsOneWidget);
    expect(tester.getTopLeft(notice).dy, lessThan(100));
    expect(tester.getTopLeft(find.byTooltip('关闭提示')).dx, greaterThan(350));
    await tester.tap(find.byTooltip('关闭提示'));
    await tester.pumpAndSettle();
    expect(notice, findsNothing);
    await _dispose(tester);
  });

  testWidgets('待办不存在时给出可读错误而不是白屏', (tester) async {
    await _pump(tester, _backend(todoId: 't-other'));

    expect(find.textContaining('专注待办不存在或日期已变更'), findsOneWidget);
  });
}

/// 退出页面以取消倒计时定时器，避免测试框架报残留 Timer。
Future<void> _dispose(WidgetTester tester) async {
  await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
  await tester.pumpAndSettle();
}

FakeBackend _backend({
  String todoId = 't-1',
  int plannedSeconds = 1500,
  String focusState = 'IDLE',
  int focusedMs = 0,
  bool requireEvidence = false,
}) {
  return FakeBackend()
    ..on('GET', '/api/v1/me', json: meJson())
    ..on(
      'GET',
      '/api/v1/todos',
      json: dayViewJson(
        todos: [
          todoJson(
            id: todoId,
            title: '法条背诵',
            todoType: 'FOCUS',
            plannedSeconds: plannedSeconds,
            focusState: focusState,
            focusedMs: focusedMs,
            status: focusState == 'IDLE' ? 'TODO' : 'IN_PROGRESS',
            requireEvidence: requireEvidence,
          ),
        ],
      ),
    );
}

Future<void> _pump(
  WidgetTester tester,
  FakeBackend backend, {
  FakeAttachmentPicker? picker,
  ScreenWakeLock? wake,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        if (wake != null) screenWakeLockProvider.overrideWithValue(wake),
        focusNowProvider.overrideWithValue(tester.binding.clock.now),
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        attachmentPickerProvider.overrideWithValue(
          picker ?? FakeAttachmentPicker(),
        ),
      ],
      child: const MaterialApp(home: FocusRunPage(todoId: 't-1')),
    ),
  );
  await tester.pumpAndSettle();
}

/// 记录常亮调用，不触碰真实设备设置。
class _Wake implements ScreenWakeLock {
  bool enabled = false;
  @override
  Future<void> enable() async {
    enabled = true;
  }

  @override
  Future<void> disable() async {
    enabled = false;
  }
}

/// 返回完整服务端快照，避免测试掩盖页面忽略响应状态的问题。
Map<String, dynamic> _actionJson(String action) => todoJson(
  id: 't-1',
  title: '法条背诵',
  todoType: 'FOCUS',
  plannedSeconds: 1500,
  focusState: switch (action) {
    'start' || 'resume' => 'RUNNING',
    'pause' => 'PAUSED',
    'stop' => 'STOPPED',
    'abandon' => 'ABANDONED',
    _ => 'FINISHED',
  },
  status: action == 'finish' ? 'DONE' : 'IN_PROGRESS',
  focusedMs: action == 'start' ? 0 : 3000,
);

/// 完成时补传的凭证元数据，测试不访问磁盘或真实相册。
const _proof = {
  'id': 'a-1',
  'filename': 'proof.png',
  'contentType': 'image/png',
  'sizeBytes': 2048,
  'sortOrder': 0,
  'createdAt': '2026-09-07T01:00:00Z',
  'downloadUrl': '/api/v1/todos/t-1/attachments/a-1/content',
};
