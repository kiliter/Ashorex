import 'dart:async';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/presence/nag_event_service.dart';

/// 用可控流和测试时钟模拟弱网，不访问真实网络，也不依赖 sleep。
void main() {
  testWidgets('默认不开流，SSE 断线退避重连，切回心跳取消连接', (tester) async {
    final streams = <StreamController<String>>[];
    var notified = 0;
    final service = NagEventService(
      connect: () {
        final stream = StreamController<String>();
        streams.add(stream);
        return stream.stream;
      },
      onPendingNag: () => notified++,
    );
    service.start();
    service.didChangeAppLifecycleState(AppLifecycleState.resumed);
    expect(streams, isEmpty);
    service.setMode('SSE');
    expect(streams.length, 1);
    streams.first.add('ready');
    await tester.pump();
    expect(notified, 1);
    streams.first.addError(StateError('网络断开'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 999));
    expect(streams.length, 1);
    await tester.pump(const Duration(milliseconds: 1));
    expect(streams.length, 2);
    streams[1].addError(StateError('仍未联网'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(streams.length, 2);
    await tester.pump(const Duration(seconds: 1));
    expect(streams.length, 3);
    service.setMode('HEARTBEAT');
    expect(streams.last.hasListener, isFalse);
    await tester.pump(const Duration(minutes: 1));
    expect(streams.length, 3);
    service.dispose();
    for (final stream in streams) {
      unawaited(stream.close());
    }
    await tester.pump();
  });

  testWidgets('后台断开，回前台重连补查，注销后不重连', (tester) async {
    final streams = <StreamController<String>>[];
    var notified = 0;
    final service = NagEventService(
      connect: () {
        final stream = StreamController<String>();
        streams.add(stream);
        return stream.stream;
      },
      onPendingNag: () => notified++,
    );
    service.start();
    service.didChangeAppLifecycleState(AppLifecycleState.resumed);
    service.setMode('SSE');
    service.didChangeAppLifecycleState(AppLifecycleState.paused);
    expect(streams.first.hasListener, isFalse);
    await tester.pump(const Duration(minutes: 1));
    expect(streams.length, 1);
    service.didChangeAppLifecycleState(AppLifecycleState.resumed);
    expect(streams.length, 2);
    streams.last.add('ready');
    await tester.pump();
    expect(notified, 1);
    service.dispose();
    expect(streams.last.hasListener, isFalse);
    service.setMode('SSE');
    await tester.pump(const Duration(minutes: 1));
    expect(streams.length, 2);
    for (final stream in streams) {
      unawaited(stream.close());
    }
    await tester.pump();
  });
}
