import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/player/domain/learning_player_state.dart';
import 'package:shangan_ios/features/player/presentation/learning_player_controller.dart';

void main() {
  late _FakePlayerAdapter player;
  late _FakeWatchRepository repository;
  late LearningPlayerController controller;

  setUp(() async {
    player = _FakePlayerAdapter();
    repository = _FakeWatchRepository();
    controller = LearningPlayerController(
      repository: repository,
      player: player,
    );
    await controller.initialize(lessonId: 'lesson-1', planItemId: 'item-1');
  });

  tearDown(() async {
    await controller.close();
    await player.positions.close();
  });

  test('前台播放会发送包含单调序号和当前位置的心跳', () async {
    player.positions.add(const Duration(seconds: 10));
    await Future<void>.delayed(Duration.zero);
    await controller.play();

    await controller.sendHeartbeat();

    expect(repository.lastHeartbeat?.sequence, 1);
    expect(repository.lastHeartbeat?.positionMs, 10000);
    expect(repository.lastHeartbeat?.playing, isTrue);
    expect(repository.lastHeartbeat?.foreground, isTrue);
  });

  test('进入后台立即暂停且后台心跳不计为播放', () async {
    await controller.play();

    await controller.setForeground(false);

    expect(player.pauseCount, 1);
    expect(controller.state.isForeground, isFalse);
    expect(controller.state.isPlaying, isFalse);
  });

  test('服务端拒绝前跳时回退到可信位置', () async {
    repository.nextHeartbeat = const WatchHeartbeatData(
      trustedPositionMs: 5000,
      verifiedWatchMs: 5000,
      seekAllowed: false,
      aliveCheckRequired: false,
      completed: false,
      status: 'ACTIVE',
    );
    player.positions.add(const Duration(seconds: 30));
    await Future<void>.delayed(Duration.zero);

    await controller.sendHeartbeat();

    expect(player.lastSeek, const Duration(seconds: 5));
  });

  test('连续三次心跳失败后暂停并展示网络错误', () async {
    repository.heartbeatError = StateError('offline');
    await controller.play();

    await controller.sendHeartbeat();
    await controller.sendHeartbeat();
    await controller.sendHeartbeat();

    expect(player.pauseCount, 1);
    expect(controller.state.networkError, isTrue);
    expect(controller.state.heartbeatFailures, 3);
  });

  test('验活触发后暂停，只有用户明确确认才恢复播放', () async {
    repository.nextHeartbeat = const WatchHeartbeatData(
      trustedPositionMs: 10000,
      verifiedWatchMs: 10000,
      seekAllowed: true,
      aliveCheckRequired: true,
      completed: false,
      status: 'PAUSED',
    );
    await controller.play();

    await controller.sendHeartbeat();
    expect(player.pauseCount, 1);
    expect(controller.state.aliveCheckRequired, isTrue);
    expect(player.playCount, 1);

    await controller.confirmAliveCheck();
    expect(controller.state.aliveCheckRequired, isFalse);
    expect(player.playCount, 2);
  });
}

final class _FakePlayerAdapter implements PlayerAdapter {
  final positions = StreamController<Duration>.broadcast();
  int playCount = 0;
  int pauseCount = 0;
  Duration? lastSeek;

  @override
  Stream<Duration> get positionStream => positions.stream;

  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {}

  @override
  Future<void> play() async => playCount++;

  @override
  Future<void> pause() async => pauseCount++;

  @override
  Future<void> seek(Duration position) async => lastSeek = position;

  @override
  Future<void> dispose() async {}
}

final class _FakeWatchRepository implements WatchRepository {
  WatchHeartbeatCommand? lastHeartbeat;
  Object? heartbeatError;
  WatchHeartbeatData nextHeartbeat = const WatchHeartbeatData(
    trustedPositionMs: 10000,
    verifiedWatchMs: 10000,
    seekAllowed: true,
    aliveCheckRequired: false,
    completed: false,
    status: 'ACTIVE',
  );

  @override
  Future<WatchSessionData> createSession(
    String lessonId, {
    String? planItemId,
  }) async => WatchSessionData(
    sessionId: 'session-1',
    ticketUri: Uri.parse('https://example.test/playback/ticket/stream'),
    trustedPositionMs: 0,
    durationMs: 600000,
    heartbeatIntervalSeconds: 10,
  );

  @override
  Future<WatchHeartbeatData> heartbeat(
    String sessionId,
    WatchHeartbeatCommand command,
  ) async {
    lastHeartbeat = command;
    if (heartbeatError case final error?) throw error;
    return nextHeartbeat;
  }

  @override
  Future<WatchHeartbeatData> confirmAliveCheck(String sessionId) async =>
      const WatchHeartbeatData(
        trustedPositionMs: 10000,
        verifiedWatchMs: 10000,
        seekAllowed: true,
        aliveCheckRequired: false,
        completed: false,
        status: 'ACTIVE',
      );

  @override
  Future<void> stop(String sessionId) async {}
}
