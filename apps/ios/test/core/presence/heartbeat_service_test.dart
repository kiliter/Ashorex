import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/presence/heartbeat_service.dart';

import '../../support/fake_backend.dart';

/// 心跳只更新在线状态：间隔由服务端下发，失败只标记离线，
/// 收到 `pendingNagId` 时必须回调拉起全屏催办。心跳本身不是有效操作。
///
/// 服务持有周期定时器，因此每个用例都必须在测试体内 dispose，
/// 否则 Flutter 测试框架会因残留 Timer 直接失败。
void main() {
  testWidgets('成功心跳触发离线重放并读取最新队列深度', (tester) async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/heartbeat', json: {'heartbeatIntervalSeconds': 60});
    var pending = 2;
    var replays = 0;
    final service = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
      queueDepth: () => pending,
      onConnected: () async {
        replays++;
        pending = 0;
      },
    );
    service.start();
    await tester.pumpAndSettle();
    expect(
      backend.lastRequest('POST', '/api/v1/heartbeat').json['queuedEvents'],
      2,
    );
    expect(replays, 1);
    expect(service.queuedEvents, 0);
    service.dispose();
  });

  testWidgets('心跳下发 SSE 模式，旧响应缺少字段时回退轮询', (tester) async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/heartbeat',
        json: {'heartbeatIntervalSeconds': 60, 'nagTransportMode': 'SSE'},
      );
    final modes = <String>[];
    final service = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
      onTransportMode: modes.add,
    );
    service.start();
    await tester.pumpAndSettle();
    backend.on(
      'POST',
      '/api/v1/heartbeat',
      json: {'heartbeatIntervalSeconds': 60},
    );
    await tester.pump(const Duration(seconds: 60));
    await tester.pumpAndSettle();
    expect(modes, ['SSE', 'HEARTBEAT']);
    service.dispose();
  });

  testWidgets('首次心跳成功后标记在线并上报队列深度与版本', (tester) async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/heartbeat',
        json: {'heartbeatIntervalSeconds': 60, 'minReasonLength': 5},
      );
    final service = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
    );
    service.reportQueueDepth(3);

    service.start();
    await tester.pumpAndSettle();

    final body = backend.lastRequest('POST', '/api/v1/heartbeat').json;
    expect(body['appState'], 'FOREGROUND');
    expect(body['clientVersion'], '2.0.0');
    expect(body['queuedEvents'], 3);
    expect(service.online, isTrue);
    expect(service.lastSuccessAt, isNotNull);
    service.dispose();
  });

  testWidgets('心跳失败只标记离线，不抛出也不影响本地计时', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/heartbeat', status: 503);
    final service = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
    );

    service.start();
    await tester.pumpAndSettle();

    expect(service.online, isFalse);
    expect(service.lastSuccessAt, isNull);
    service.dispose();
  });

  testWidgets('服务端下发待回应催办时回调一次并带上催办 ID', (tester) async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/heartbeat',
        json: {
          'heartbeatIntervalSeconds': 60,
          'minReasonLength': 5,
          'pendingNagId': 'n-9',
          'pendingNagMessage': '已经 95 分钟没动了',
        },
      );
    final received = <String>[];
    final service = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
      onPendingNag: received.add,
    );

    service.start();
    await tester.pumpAndSettle();

    expect(received, ['n-9']);
    service.dispose();
  });

  testWidgets('服务端改小间隔后按新间隔继续心跳', (tester) async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/heartbeat',
        json: {'heartbeatIntervalSeconds': 5, 'minReasonLength': 5},
      );
    final service = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
    );

    service.start();
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/heartbeat'), 1);

    // 用测试时钟推进 12 秒：5 秒间隔应再触发两次；若仍是默认 60 秒则一次都不会触发。
    await tester.pump(const Duration(seconds: 12));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/heartbeat'), 3);
    service.dispose();
  });

  testWidgets('切到后台会立即补一次心跳并标注 BACKGROUND', (tester) async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/heartbeat',
        json: {'heartbeatIntervalSeconds': 60, 'minReasonLength': 5},
      );
    final service = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
    );

    service.start();
    await tester.pumpAndSettle();

    service.didChangeAppLifecycleState(AppLifecycleState.paused);
    await tester.pumpAndSettle();

    expect(backend.callCount('POST', '/api/v1/heartbeat'), 2);
    expect(
      backend.lastRequest('POST', '/api/v1/heartbeat').json['appState'],
      'BACKGROUND',
    );
    service.dispose();
  });

  testWidgets('dispose 后不再继续心跳', (tester) async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/heartbeat',
        json: {'heartbeatIntervalSeconds': 5, 'minReasonLength': 5},
      );
    final service = HeartbeatService(
      repository: buildRepository(backend),
      clientVersion: '2.0.0',
    );

    service.start();
    await tester.pumpAndSettle();
    service.dispose();
    await tester.pump(const Duration(seconds: 30));
    await tester.pumpAndSettle();

    expect(backend.callCount('POST', '/api/v1/heartbeat'), 1);
  });
}
