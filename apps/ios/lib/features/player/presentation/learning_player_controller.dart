import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/player/domain/learning_player_state.dart';
import 'package:video_player/video_player.dart';

/// 对 Flutter 官方 video_player 的薄适配，业务控制器不依赖插件静态 API。
final class VideoPlayerAdapter implements PlayerAdapter {
  final StreamController<Duration> _positions =
      StreamController<Duration>.broadcast();
  VideoPlayerController? _controller;
  bool _disposed = false;

  VideoPlayerController? get videoController => _controller;

  @override
  Stream<Duration> get positionStream => _positions.stream;

  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {
    final previous = _controller;
    if (previous != null) {
      previous.removeListener(_emitPosition);
      await previous.dispose();
    }
    final controller = VideoPlayerController.networkUrl(
      uri,
      httpHeaders: headers,
    );
    _controller = controller;
    await controller.initialize();
    controller.addListener(_emitPosition);
    _emitPosition();
  }

  void _emitPosition() {
    if (!_disposed && !_positions.isClosed) {
      _positions.add(_controller?.value.position ?? Duration.zero);
    }
  }

  @override
  Future<void> play() async => _controller?.play();

  @override
  Future<void> pause() async => _controller?.pause();

  @override
  Future<void> seek(Duration position) async => _controller?.seekTo(position);

  @override
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    final controller = _controller;
    controller?.removeListener(_emitPosition);
    await controller?.dispose();
    await _positions.close();
  }
}

/// 编排观看会话、播放器控制、前后台暂停、心跳纠偏和验活恢复。
final class LearningPlayerController extends ChangeNotifier {
  factory LearningPlayerController({
    required WatchRepository repository,
    required PlayerAdapter player,
  }) => LearningPlayerController._(repository, player);

  LearningPlayerController._(this._repository, this._player);

  final WatchRepository _repository;
  final PlayerAdapter _player;

  LearningPlayerState _state = const LearningPlayerState();
  LearningPlayerState get state => _state;

  StreamSubscription<Duration>? _positionSubscription;
  Timer? _heartbeatTimer;
  int _sequence = 0;
  bool _heartbeatInFlight = false;
  bool _stopped = false;
  bool _closed = false;

  Future<void> initialize({
    required String lessonId,
    String? planItemId,
  }) async {
    _positionSubscription = _player.positionStream.listen((position) {
      _setState(_state.copyWith(position: position));
    });
    final session = await _repository.createSession(
      lessonId,
      planItemId: planItemId,
    );
    await _player.open(session.ticketUri);
    final trusted = Duration(milliseconds: session.trustedPositionMs);
    if (trusted > Duration.zero) await _player.seek(trusted);
    _setState(
      _state.copyWith(
        sessionId: session.sessionId,
        ticketUri: session.ticketUri,
        duration: Duration(milliseconds: session.durationMs),
        position: trusted,
        maxVerifiedPosition: trusted,
        initialized: true,
        status: 'ACTIVE',
      ),
    );
    _heartbeatTimer = Timer.periodic(
      Duration(seconds: session.heartbeatIntervalSeconds),
      (_) => unawaited(sendHeartbeat()),
    );
  }

  Future<void> play() async {
    if (_state.aliveCheckRequired || _state.completed || !_state.isForeground) {
      return;
    }
    await _player.play();
    _setState(
      _state.copyWith(isPlaying: true, networkError: false, status: 'ACTIVE'),
    );
  }

  Future<void> pause() async {
    await _player.pause();
    _setState(_state.copyWith(isPlaying: false));
  }

  /// App 离开前台时先暂停再上报，恢复前台后不自动播放。
  Future<void> setForeground(bool foreground) async {
    if (!foreground) await pause();
    _setState(_state.copyWith(isForeground: foreground));
    await sendHeartbeat();
  }

  /// 客户端拖动也做第一层限制；最终可信位置仍由服务端心跳裁决。
  Future<void> seek(Duration target) async {
    final safeTarget = target < Duration.zero
        ? Duration.zero
        : target > _state.maxVerifiedPosition
        ? _state.maxVerifiedPosition
        : target;
    await _player.seek(safeTarget);
    _setState(_state.copyWith(position: safeTarget));
  }

  Future<void> sendHeartbeat() async {
    final sessionId = _state.sessionId;
    if (sessionId == null || _closed || _heartbeatInFlight || _stopped) return;
    _heartbeatInFlight = true;
    final sequence = ++_sequence;
    try {
      final response = await _repository.heartbeat(
        sessionId,
        WatchHeartbeatCommand(
          sequence: sequence,
          positionMs: _state.position.inMilliseconds,
          playing: _state.isPlaying,
          foreground: _state.isForeground,
        ),
      );
      final trusted = Duration(milliseconds: response.trustedPositionMs);
      if (!response.seekAllowed) {
        await _player.seek(trusted);
      }
      var playing = _state.isPlaying;
      if (response.aliveCheckRequired || response.completed) {
        await _player.pause();
        playing = false;
      }
      _setState(
        _state.copyWith(
          position: response.seekAllowed ? _state.position : trusted,
          maxVerifiedPosition: trusted > _state.maxVerifiedPosition
              ? trusted
              : _state.maxVerifiedPosition,
          isPlaying: playing,
          aliveCheckRequired: response.aliveCheckRequired,
          networkError: false,
          heartbeatFailures: 0,
          completed: response.completed,
          status: response.status,
        ),
      );
    } catch (_) {
      final failures = _state.heartbeatFailures + 1;
      if (failures >= 3) await _player.pause();
      _setState(
        _state.copyWith(
          heartbeatFailures: failures,
          networkError: failures >= 3,
          isPlaying: failures >= 3 ? false : _state.isPlaying,
        ),
      );
    } finally {
      _heartbeatInFlight = false;
    }
  }

  /// 只有用户点击验活确认按钮才调用此方法，成功后才恢复播放。
  Future<void> confirmAliveCheck() async {
    final sessionId = _state.sessionId;
    if (sessionId == null || !_state.aliveCheckRequired) return;
    final response = await _repository.confirmAliveCheck(sessionId);
    _setState(
      _state.copyWith(
        aliveCheckRequired: false,
        networkError: false,
        heartbeatFailures: 0,
        completed: response.completed,
        status: response.status,
      ),
    );
    if (!response.completed && _state.isForeground) await play();
  }

  /// 显式返回和页面销毁都会调用；服务端 stop 保持幂等。
  Future<void> stop() async {
    if (_stopped) return;
    _stopped = true;
    _heartbeatTimer?.cancel();
    final sessionId = _state.sessionId;
    if (sessionId != null) await _repository.stop(sessionId);
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _heartbeatTimer?.cancel();
    await _positionSubscription?.cancel();
    try {
      await stop();
    } catch (_) {
      // 页面销毁不能因网络失败阻塞本地播放器资源释放。
    }
    await _player.dispose();
    super.dispose();
  }

  void _setState(LearningPlayerState value) {
    if (_closed) return;
    _state = value;
    notifyListeners();
  }
}
