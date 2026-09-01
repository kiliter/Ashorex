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
    try {
      // AVPlayer 遇到损坏或不可达的 HLS 分片时可能长期不回调，超时后交给页面显示失败状态。
      await controller.initialize().timeout(const Duration(seconds: 20));
    } catch (_) {
      if (identical(_controller, controller)) _controller = null;
      await controller.dispose();
      rethrow;
    }
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
  Future<void> setPlaybackSpeed(double speed) async =>
      _controller?.setPlaybackSpeed(speed);

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
  Future<void>? _sessionStartFuture;
  String? _lessonId;
  String? _planItemId;
  int _sequence = 0;
  Future<void>? _heartbeatOperation;
  bool _stopped = false;
  bool _closed = false;

  Future<void> initialize({
    required String lessonId,
    String? planItemId,
    Duration duration = Duration.zero,
    Duration trustedPosition = Duration.zero,
  }) async {
    _lessonId = lessonId;
    _planItemId = planItemId;
    _positionSubscription = _player.positionStream.listen((position) {
      _setState(_state.copyWith(position: position));
    });
    // 进入播放页只准备本地状态；服务端会话必须等用户第一次点击播放后再创建。
    _setState(
      _state.copyWith(
        duration: duration,
        position: trustedPosition,
        maxVerifiedPosition: trustedPosition,
        initialized: true,
        status: 'READY',
      ),
    );
  }

  /// 首次点击播放时原子创建会话，避免连续点击创建多个可信观看会话。
  Future<void> _startSession() async {
    final lessonId = _lessonId;
    if (lessonId == null) throw StateError('播放器尚未初始化');
    _setState(
      _state.copyWith(
        preparingPlayback: true,
        playbackStartError: false,
        status: 'STARTING',
      ),
    );
    try {
      final session = await _repository.createSession(
        lessonId,
        planItemId: _planItemId,
      );
      if (_closed || _stopped) {
        // 用户在会话创建返回前已经退出时，立即回收服务端会话，不能遗留幽灵学习记录。
        await _repository.stop(session.sessionId);
        return;
      }
      await _player.open(session.ticketUri);
      if (_closed || _stopped) {
        await _repository.stop(session.sessionId);
        return;
      }
      if (_state.playbackSpeed != 1.0) {
        await _player.setPlaybackSpeed(_state.playbackSpeed);
      }
      final trusted = Duration(milliseconds: session.trustedPositionMs);
      // 复习快捷入口从头打开且允许全程拖动，不把已完成位置当成续播点。
      final initialPosition = session.review ? Duration.zero : trusted;
      if (initialPosition > Duration.zero) await _player.seek(initialPosition);
      _setState(
        _state.copyWith(
          sessionId: session.sessionId,
          ticketUri: session.ticketUri,
          duration: Duration(milliseconds: session.durationMs),
          position: initialPosition,
          maxVerifiedPosition: trusted,
          reviewMode: session.review,
          initialized: true,
          preparingPlayback: false,
          playbackStartError: false,
          status: 'ACTIVE',
        ),
      );
      _heartbeatTimer = Timer.periodic(
        Duration(seconds: session.heartbeatIntervalSeconds),
        (_) => unawaited(sendHeartbeat()),
      );
    } catch (_) {
      _setState(
        _state.copyWith(
          preparingPlayback: false,
          playbackStartError: true,
          status: 'READY',
        ),
      );
      rethrow;
    }
  }

  Future<void> play() async {
    if (_state.aliveCheckRequired ||
        _state.completed ||
        !_state.isForeground ||
        _state.preparingPlayback) {
      return;
    }
    if (_state.sessionId == null) {
      final pending = _sessionStartFuture;
      if (pending != null) return;
      late final Future<void> created;
      created = _startSession().whenComplete(() {
        if (identical(_sessionStartFuture, created)) {
          _sessionStartFuture = null;
        }
      });
      _sessionStartFuture = created;
      try {
        await created;
      } catch (_) {
        return;
      }
    }
    if (_state.sessionId == null || _closed || _stopped) return;
    try {
      await _player.play();
      _setState(
        _state.copyWith(
          isPlaying: true,
          networkError: false,
          playbackStartError: false,
          status: 'ACTIVE',
        ),
      );
    } catch (_) {
      _setState(_state.copyWith(playbackStartError: true, isPlaying: false));
    }
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
    final maximum = _state.reviewMode
        ? _state.duration
        : _state.maxVerifiedPosition;
    final safeTarget = target < Duration.zero
        ? Duration.zero
        : target > maximum
        ? maximum
        : target;
    await _player.seek(safeTarget);
    _setState(_state.copyWith(position: safeTarget));
  }

  /// 在常用倍速之间循环，避免播放器控制层再弹出遮挡画面的菜单。
  Future<void> cyclePlaybackSpeed() async {
    const speeds = <double>[1.0, 1.25, 1.5, 2.0];
    final currentIndex = speeds.indexOf(_state.playbackSpeed);
    final next = speeds[(currentIndex + 1) % speeds.length];
    if (_state.isPlaying && _state.sessionId != null) {
      // 先用旧倍速结算当前区间，避免服务端把整个心跳间隔误按新倍速计算。
      final pending = _heartbeatOperation;
      if (pending != null) await pending;
      await sendHeartbeat();
    }
    await _player.setPlaybackSpeed(next);
    _setState(_state.copyWith(playbackSpeed: next));
  }

  Future<void> sendHeartbeat() {
    final sessionId = _state.sessionId;
    if (sessionId == null || _closed || _stopped) return Future<void>.value();
    final pending = _heartbeatOperation;
    if (pending != null) return pending;
    late final Future<void> operation;
    operation = _sendHeartbeat(sessionId).whenComplete(() {
      if (identical(_heartbeatOperation, operation)) {
        _heartbeatOperation = null;
      }
    });
    _heartbeatOperation = operation;
    return operation;
  }

  /// 实际执行一次心跳；公开入口负责把并发调用合并到同一个 Future。
  Future<void> _sendHeartbeat(String sessionId) async {
    final sequence = ++_sequence;
    try {
      final response = await _repository.heartbeat(
        sessionId,
        WatchHeartbeatCommand(
          sequence: sequence,
          positionMs: _state.position.inMilliseconds,
          playing: _state.isPlaying,
          foreground: _state.isForeground,
          playbackSpeed: _state.playbackSpeed,
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
