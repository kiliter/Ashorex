import 'dart:async';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/presence/app_activity.dart';
import 'package:shangan_ios/core/presence/heartbeat_service.dart';
import '../../support/fake_backend.dart';

/// 验证真实页面遮挡和传输排序，而非只验证字段拼接。
void main() {
  testWidgets('催办覆盖视频时只上报催办，返回恢复视频暂停位置', (tester) async {
    final tracker = AppActivityTracker();
    late BuildContext pageContext;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appActivityProvider.overrideWithValue(tracker)],
        child: MaterialApp(
          navigatorObservers: [activityRouteObserver],
          home: AppActivityScope(
            activity: const AppActivity('PLAYER', 'VIDEO_PAUSED', 't-1'),
            child: Builder(
              builder: (context) {
                pageContext = context;
                return const Scaffold();
              },
            ),
          ),
        ),
      ),
    );
    expect(tracker.current, const AppActivity('PLAYER', 'VIDEO_PAUSED', 't-1'));
    Navigator.of(pageContext).push(
      MaterialPageRoute<void>(
        builder: (_) => const AppActivityScope(
          activity: AppActivity('NAG', 'RESPONDING'),
          child: Scaffold(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(tracker.current, const AppActivity('NAG', 'RESPONDING'));
    Navigator.of(pageContext).pop();
    await tester.pumpAndSettle();
    expect(tracker.current, const AppActivity('PLAYER', 'VIDEO_PAUSED', 't-1'));
    await tester.pumpWidget(const SizedBox());
    tracker.dispose();
  });

  testWidgets('慢心跳期间切页只串行补最新状态，后台标识独立于专注', (tester) async {
    final backend = _DeferredBackend();
    final tracker = AppActivityTracker();
    final owner = Object();
    tracker.update(owner, const AppActivity('HOME', 'BROWSING'));
    final heartbeat = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
      activity: tracker,
    );
    heartbeat.start();
    await tester.pumpAndSettle();
    expect(backend.bodies, hasLength(1));
    tracker.update(owner, const AppActivity('PLAYER', 'VIDEO_PLAYING', 't-1'));
    await tester.pump(const Duration(milliseconds: 150));
    tracker.update(owner, const AppActivity('FOCUS', 'FOCUS_RUNNING', 't-2'));
    heartbeat.didChangeAppLifecycleState(AppLifecycleState.paused);
    await tester.pump(const Duration(milliseconds: 150));
    expect(backend.bodies, hasLength(1));
    backend.responses.first.complete(_response());
    await tester.pumpAndSettle();
    expect(backend.bodies, hasLength(2));
    expect(backend.bodies.last['currentPage'], 'FOCUS');
    expect(backend.bodies.last['activityState'], 'FOCUS_RUNNING');
    expect(backend.bodies.last['activityTodoId'], 't-2');
    expect(backend.bodies.last['appState'], 'BACKGROUND');
    backend.responses.last.complete(_response());
    await tester.pumpAndSettle();
    tracker.update(owner, const AppActivity('FOCUS', 'FOCUS_RUNNING', 't-2'));
    await tester.pump(const Duration(milliseconds: 200));
    expect(backend.bodies, hasLength(2));
    heartbeat.dispose();
    tracker.dispose();
  });

  testWidgets('断网恢复只发当前位置，不重放旧页面历史', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/heartbeat', status: 503);
    final tracker = AppActivityTracker();
    final owner = Object();
    tracker.update(
      owner,
      const AppActivity('PLAYER', 'VIDEO_BUFFERING', 't-1'),
    );
    final heartbeat = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
      activity: tracker,
    );
    heartbeat.start();
    await tester.pumpAndSettle();
    expect(heartbeat.online, isFalse);
    backend.on(
      'POST',
      '/api/v1/heartbeat',
      json: {'heartbeatIntervalSeconds': 60},
    );
    tracker.update(owner, const AppActivity('PROFILE', 'BROWSING'));
    await tester.pump(const Duration(milliseconds: 150));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/heartbeat'), 2);
    expect(
      backend.lastRequest('POST', '/api/v1/heartbeat').json['currentPage'],
      'PROFILE',
    );
    expect(
      backend.lastRequest('POST', '/api/v1/heartbeat').json['activityTodoId'],
      isNull,
    );
    expect(heartbeat.online, isTrue);
    heartbeat.dispose();
    tracker.dispose();
  });

  test('离线和后台快照明确标注最后位置，旧响应显示未知', () {
    final activity = CurrentAppActivity.fromJson({
      'pageLabel': '专注页',
      'stateLabel': '专注计时中',
      'todoTitle': '背法条',
      'updatedAt': '2026-09-08T10:00:00Z',
      'background': true,
      'stale': true,
    });
    expect(activity.summary, '最后上报 · App 已切后台 · 专注页 · 专注计时中 · 背法条');
    expect(activity.updatedAt, DateTime.utc(2026, 9, 8, 10));
    expect(CurrentAppActivity.fromJson(null).summary, '最后上报 · 未知页面 · 未知状态');
  });
}

ResponseBody _response() => ResponseBody.fromString(
  '{"heartbeatIntervalSeconds":60}',
  200,
  headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  },
);

/// 可控网络完成顺序，不使用真实网络或任意睡眠。
class _DeferredBackend implements HttpClientAdapter {
  final bodies = <Map<String, dynamic>>[];
  final responses = <Completer<ResponseBody>>[];
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<void>? cancel,
  ) {
    bodies.add(Map<String, dynamic>.from(options.data as Map));
    final response = Completer<ResponseBody>();
    responses.add(response);
    return response.future;
  }

  @override
  void close({bool force = false}) {}
}
