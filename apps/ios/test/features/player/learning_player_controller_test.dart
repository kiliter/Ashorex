import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/player/domain/learning_player_state.dart';
import 'package:shangan_ios/features/player/presentation/learning_player_controller.dart';
import 'package:video_player/video_player.dart';

void main() {
  test('iOS 播放使用平台图层避免模拟器绿屏', () {
    expect(
      shanganVideoViewType(TargetPlatform.iOS),
      VideoViewType.platformView,
    );
    expect(
      shanganVideoViewType(TargetPlatform.android),
      VideoViewType.textureView,
    );
  });

  test('可信观看进度提供百分比并按服务端阈值显示已看完', () {
    const inProgress = LearningPlayerState(
      duration: Duration(seconds: 1000),
      maxVerifiedPosition: Duration(seconds: 350),
    );
    const completed = LearningPlayerState(
      duration: Duration(seconds: 1000),
      maxVerifiedPosition: Duration(seconds: 981),
    );

    expect(inProgress.trustedWatchedPercent, 35);
    expect(inProgress.trustedWatchDone, isFalse);
    expect(completed.trustedWatchedPercent, 100);
    expect(completed.trustedWatchDone, isTrue);
  });

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

  test('进入播放页不创建会话，首次连续点击播放也只创建一个会话', () async {
    expect(repository.createSessionCount, 0);
    expect(player.openCount, 0);
    expect(controller.state.status, 'READY');

    final firstPlay = controller.play();
    final repeatedPlay = controller.play();
    await Future.wait([firstPlay, repeatedPlay]);

    expect(repository.createSessionCount, 1);
    expect(player.openCount, 1);
    expect(player.playCount, 1);
    expect(controller.state.sessionId, 'session-1');
  });

  test('前台播放会发送包含单调序号和当前位置的心跳', () async {
    await controller.play();
    player.positions.add(const Duration(seconds: 10));
    await Future<void>.delayed(Duration.zero);

    await controller.sendHeartbeat();

    expect(repository.lastHeartbeat?.sequence, 1);
    expect(repository.lastHeartbeat?.positionMs, 10000);
    expect(repository.lastHeartbeat?.playing, isTrue);
    expect(repository.lastHeartbeat?.foreground, isTrue);
    expect(repository.lastHeartbeat?.playbackSpeed, 1.0);
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
    await controller.play();
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

  test('复习快捷入口从头播放并允许拖动到视频任意位置', () async {
    final reviewPlayer = _FakePlayerAdapter();
    final reviewRepository = _FakeWatchRepository(review: true);
    final reviewController = LearningPlayerController(
      repository: reviewRepository,
      player: reviewPlayer,
    );

    await reviewController.initialize(
      lessonId: 'lesson-1',
      planItemId: 'review-item-1',
    );
    await reviewController.play();
    expect(reviewController.state.position, Duration.zero);
    await reviewController.seek(const Duration(minutes: 9));

    expect(reviewController.state.reviewMode, isTrue);
    expect(reviewPlayer.lastSeek, const Duration(minutes: 9));
    await reviewController.close();
    await reviewPlayer.positions.close();
  });

  test('播放器倍速按常用档位循环并同步到底层播放器', () async {
    await controller.cyclePlaybackSpeed();
    expect(controller.state.playbackSpeed, 1.25);
    expect(player.lastPlaybackSpeed, 1.25);

    await controller.cyclePlaybackSpeed();
    await controller.cyclePlaybackSpeed();
    await controller.cyclePlaybackSpeed();

    expect(controller.state.playbackSpeed, 1.0);
    expect(player.lastPlaybackSpeed, 1.0);
  });

  test('播放中切换倍速前先按旧倍速结算当前心跳区间', () async {
    await controller.play();
    player.positions.add(const Duration(seconds: 10));
    await Future<void>.delayed(Duration.zero);

    await controller.cyclePlaybackSpeed();

    expect(repository.heartbeats, hasLength(1));
    expect(repository.heartbeats.single.playbackSpeed, 1.0);
    expect(controller.state.playbackSpeed, 1.25);
    expect(player.lastPlaybackSpeed, 1.25);
  });

  test('心跳进行中切换倍速会等待并再次按旧倍速结算', () async {
    await controller.play();
    final firstHeartbeatGate = Completer<void>();
    repository.heartbeatGate = firstHeartbeatGate;
    player.positions.add(const Duration(seconds: 5));
    await Future<void>.delayed(Duration.zero);

    final inFlight = controller.sendHeartbeat();
    await Future<void>.delayed(Duration.zero);
    player.positions.add(const Duration(seconds: 10));
    await Future<void>.delayed(Duration.zero);
    final speedChange = controller.cyclePlaybackSpeed();
    await Future<void>.delayed(Duration.zero);

    expect(repository.heartbeats, hasLength(1));
    expect(controller.state.playbackSpeed, 1.0);
    firstHeartbeatGate.complete();
    await Future.wait([inFlight, speedChange]);

    expect(repository.heartbeats, hasLength(2));
    expect(repository.heartbeats.last.positionMs, 10000);
    expect(repository.heartbeats.last.playbackSpeed, 1.0);
    expect(controller.state.playbackSpeed, 1.25);
  });
}

final class _FakePlayerAdapter implements PlayerAdapter {
  final positions = StreamController<Duration>.broadcast();
  int playCount = 0;
  int pauseCount = 0;
  int openCount = 0;
  Duration? lastSeek;
  double? lastPlaybackSpeed;

  @override
  Stream<Duration> get positionStream => positions.stream;

  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {
    openCount += 1;
  }

  @override
  Future<void> play() async => playCount++;

  @override
  Future<void> pause() async => pauseCount++;

  @override
  Future<void> seek(Duration position) async => lastSeek = position;

  @override
  Future<void> setPlaybackSpeed(double speed) async =>
      lastPlaybackSpeed = speed;

  @override
  Future<void> dispose() async {}
}

final class _FakeWatchRepository implements WatchRepository {
  _FakeWatchRepository({this.review = false});

  final bool review;
  int createSessionCount = 0;
  WatchHeartbeatCommand? lastHeartbeat;
  final List<WatchHeartbeatCommand> heartbeats = [];
  Completer<void>? heartbeatGate;
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
  }) async {
    createSessionCount += 1;
    return WatchSessionData(
      sessionId: 'session-1',
      ticketUri: Uri.parse('https://example.test/playback/ticket/stream'),
      trustedPositionMs: review ? 600000 : 0,
      durationMs: 600000,
      heartbeatIntervalSeconds: 10,
      review: review,
    );
  }

  @override
  Future<WatchHeartbeatData> heartbeat(
    String sessionId,
    WatchHeartbeatCommand command,
  ) async {
    lastHeartbeat = command;
    heartbeats.add(command);
    final gate = heartbeatGate;
    heartbeatGate = null;
    if (gate != null) await gate.future;
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
