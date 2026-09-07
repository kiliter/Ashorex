import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/player/domain/learning_player_state.dart';
import 'package:video_player/video_player.dart';

/// iOS 使用 AVPlayerLayer，避免 Impeller 把默认纹理合成成绿屏（模拟器尤其明显）。
VideoViewType shanganVideoViewType([TargetPlatform? platform]) {
  final target = platform ?? defaultTargetPlatform;
  if (target == TargetPlatform.iOS) {
    return VideoViewType.platformView;
  }
  return VideoViewType.textureView;
}

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
      viewType: shanganVideoViewType(),
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
    ScreenWakeLock wakeLock = const NoOpScreenWakeLock(),
  }) => LearningPlayerController._(repository, player, wakeLock);

  LearningPlayerController._(this._repository, this._player, this._wakeLock);

  final WatchRepository _repository;
  final PlayerAdapter _player;
  final ScreenWakeLock _wakeLock;
  Future<void> _wakeLockOperation = Future<void>.value();
  bool _wakeLockRequested = false;

  LearningPlayerState _state = const LearningPlayerState();
  LearningPlayerState get state => _state;

  StreamSubscription<Duration>? _positionSubscription;
  Timer? _heartbeatTimer;
  Future<void>? _sessionStartFuture;
  String? _lessonId;
  String? _planItemId;
  bool _preview = false;
  int _sequence = 0;
  Future<void>? _heartbeatOperation;
  bool _stopped = false;
  bool _closed = false;
  bool _forwardPending = false;

  Future<void> initialize({
    required String lessonId,
    String? planItemId,
    bool preview = false,
    Duration duration = Duration.zero,
    Duration trustedPosition = Duration.zero,
  }) async {
    _lessonId = lessonId;
    _planItemId = preview ? null : planItemId;
    _preview = preview;
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
      // 复习快捷入口从头打开且允许按钮回看全片，不把已完成位置当成续播点。
      final initialPosition = session.review ? Duration.zero : trusted;
      if (initialPosition > Duration.zero) await _player.seek(initialPosition);
      _setState(
        _state.copyWith(
          sessionId: session.sessionId,
          ticketUri: session.ticketUri,
          duration: Duration(milliseconds: session.durationMs),
          position: initialPosition,
          maxVerifiedPosition: trusted,
          reviewMode: session.review || _preview,
          initialized: true,
          preparingPlayback: false,
          playbackStartError: false,
          status: 'ACTIVE',
        ),
      );
      if (!_preview) {
        _heartbeatTimer = Timer.periodic(
          Duration(seconds: session.heartbeatIntervalSeconds),
          (_) => unawaited(sendHeartbeat()),
        );
      }
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
        (_state.completed && !_state.reviewMode) ||
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
    // 创建会话与加载视频期间也可能切后台，异步返回后必须再次检查前台状态。
    if (_state.sessionId == null ||
        _closed ||
        _stopped ||
        !_state.isForeground) {
      return;
    }
    try {
      await _player.play();
      if (!_state.isForeground || _closed || _stopped) {
        await _player.pause();
        return;
      }
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
    // 先保存生命周期状态，防止正在完成的异步播放把后台状态覆盖回播放中。
    _setState(_state.copyWith(isForeground: foreground));
    if (!foreground) await pause();
    if (!_preview) await sendHeartbeat();
  }

  /// 快退和复习按钮使用已通过区间；普通学习向前跳过未看内容必须使用快进授权。
  Future<void> seek(Duration target) async {
    if (_forwardPending || _closed || _stopped || _state.aliveCheckRequired) {
      return;
    }
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

  /// 心跳与快进串行，连续点击中的重复调用合并，避免旧心跳覆盖授权后的跳转点。
  Future<void> fastForward() async {
    if (_forwardPending ||
        _closed ||
        _stopped ||
        _state.sessionId == null ||
        (_state.completed && !_state.reviewMode) ||
        _state.aliveCheckRequired ||
        !_state.isForeground) {
      return;
    }
    if (_state.reviewMode) {
      await seek(_state.position + const Duration(seconds: 10));
      return;
    }
    _forwardPending = true;
    try {
      final pending = _heartbeatOperation;
      if (pending != null) await pending;
      if (_closed ||
          _stopped ||
          (_state.completed && !_state.reviewMode) ||
          _state.aliveCheckRequired ||
          !_state.isForeground) {
        return;
      }
      final operation = _requestFastForward();
      // 定时器和生命周期只等待结束；快进错误由按钮调用方报告，避免合并请求泄漏异常。
      final settled = operation.catchError((Object _) {});
      _heartbeatOperation = settled;
      try {
        await operation;
      } finally {
        if (identical(_heartbeatOperation, settled)) {
          _heartbeatOperation = null;
        }
      }
    } finally {
      _forwardPending = false;
    }
  }

  /// 只有授权成功后才移动播放器；请求失败暂停并交由页面提示，不能本地猜测跳过结果。
  Future<void> _requestFastForward() async {
    try {
      final result = await _repository.fastForward(
        _state.sessionId!,
        WatchHeartbeatCommand(
          sequence: ++_sequence,
          positionMs: _state.position.inMilliseconds,
          playing: _state.isPlaying,
          foreground: _state.isForeground,
          playbackSpeed: _state.playbackSpeed,
        ),
      );
      if (_closed || _stopped) return;
      final response = result.progress;
      final target = Duration(milliseconds: result.positionMs);
      await _player.seek(target);
      var playing = _state.isPlaying;
      if (response.aliveCheckRequired ||
          (response.completed && !_state.reviewMode)) {
        await _player.pause();
        playing = false;
      }
      _setState(
        _state.copyWith(
          position: target,
          maxVerifiedPosition: Duration(
            milliseconds: response.trustedPositionMs,
          ),
          isPlaying: playing,
          aliveCheckRequired: response.aliveCheckRequired,
          completed: response.completed,
          status: response.status,
          networkError: false,
          heartbeatFailures: 0,
        ),
      );
    } catch (_) {
      if (!_closed && !_stopped) await pause();
      rethrow;
    }
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
    if (_preview || sessionId == null || _closed || _stopped) {
      return Future<void>.value();
    }
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
      if (response.aliveCheckRequired ||
          (response.completed && !_state.reviewMode)) {
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
    _syncWakeLock();
    _heartbeatTimer?.cancel();
    final sessionId = _state.sessionId;
    if (sessionId != null) await _repository.stop(sessionId);
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _syncWakeLock();
    await _wakeLockOperation;
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

  /// 只在前台播放时保持常亮；串行执行插件调用，保证退出释放不会被较慢的启用覆盖。
  void _syncWakeLock() {
    final enabled =
        !_closed &&
        !_stopped &&
        _state.isPlaying &&
        _state.isForeground &&
        (_state.duration == Duration.zero || _state.position < _state.duration);
    if (enabled == _wakeLockRequested) return;
    _wakeLockRequested = enabled;
    _wakeLockOperation = _wakeLockOperation.then((_) async {
      try {
        if (enabled) {
          await _wakeLock.enable();
        } else {
          await _wakeLock.disable();
        }
      } catch (_) {
        // 系统常亮失败不应中断播放或阻止后续资源回收，也不输出平台异常细节。
        debugPrint('播放器屏幕常亮设置失败');
      }
    });
  }

  void _setState(LearningPlayerState value) {
    if (_closed) return;
    _state = value;
    _syncWakeLock();
    notifyListeners();
  }
}
