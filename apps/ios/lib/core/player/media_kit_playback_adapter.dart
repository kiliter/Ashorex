import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log_redactor.dart';
import 'package:media_kit/media_kit.dart' as mk;
import 'package:media_kit_video/media_kit_video.dart' as mv;
import 'package:shangan_ios/core/player/playback_adapter.dart';

/// 将 media_kit 原生状态转换成学习页接口，不在内核层累计学习时长。
final class MediaKitPlaybackAdapter extends PlaybackAdapter {
  MediaKitPlaybackAdapter({
    mk.Player Function()? createPlayer,
    mv.VideoController Function(mk.Player)? createController,
  }) : _createPlayer = createPlayer ?? _newPlayer,
       _createController = createController ?? mv.VideoController.new;

  final mk.Player Function() _createPlayer;
  final mv.VideoController Function(mk.Player) _createController;
  final _subscriptions = <StreamSubscription<dynamic>>[];
  mk.Player? _player;
  mv.VideoController? _controller;
  bool _disposed = false;
  bool _authenticationExpired = false;
  bool _playing = false;
  bool _ended = false;
  bool _buffering = false;
  int _position = 0;
  int _duration = 0;
  String? _error;
  final _ready = Completer<void>();
  Timer? _positionNotification;
  static int _nextSession = 0;
  final int _session = ++_nextSession;
  final Stopwatch _elapsed = Stopwatch()..start();
  int _windowSecond = -1;
  int _nativeLines = 0;
  int _suppressed = 0;
  int _lastPositionLogMs = -5000;

  /// 在状态变更前后记录同一会话与单调时钟，保留错误被清理前的证据。
  void _diagnose(String event, [Map<String, Object?> extra = const {}]) {
    DiagnosticLog.info('player.native', event, {
      'session': _session,
      'elapsedMs': _elapsed.elapsedMilliseconds,
      'positionMs': _position,
      'durationMs': _duration,
      'playing': _playing,
      'ended': _ended,
      'buffering': _buffering,
      'hasError': _error != null,
      ...extra,
    });
  }

  /// 原生日志可能包含任意路径和请求头；敏感行整体隐藏，避免空格路径漏脱敏。
  static String _safeNativeText(String text) {
    final sensitive = RegExp(
      r'authorization|cookie|bearer|token|password|passwd|secret|api.?key|sendkey|range\s*[:=]|https?://|[/\\]|filename|file-path',
      caseSensitive: false,
    );
    return text
        .split('\n')
        .map((line) {
          if (sensitive.hasMatch(line)) return '[敏感内核行已隐藏]';
          final safe = const DiagnosticLogRedactor().redact(line);
          return safe.length > 2000 ? '${safe.substring(0, 2000)}[截断]' : safe;
        })
        .take(20)
        .join(' | ');
  }

  /// 非错误内核消息每秒最多记录 40 条；错误不丢弃，省略数量在下一窗口或销毁时汇总。
  void _nativeLog(mk.PlayerLog log) {
    final second = _elapsed.elapsedMilliseconds ~/ 1000;
    if (second != _windowSecond) {
      if (_suppressed > 0) {
        _diagnose('native logs suppressed', {'count': _suppressed});
      }
      _windowSecond = second;
      _nativeLines = 0;
      _suppressed = 0;
    }
    final important = ['error', 'fatal', 'warn'].contains(log.level);
    if (!important && _nativeLines++ >= 40) {
      _suppressed++;
      return;
    }
    _diagnose('libmpv', {
      'prefix': _safeNativeText(log.prefix),
      'level': _safeNativeText(log.level),
      'text': _safeNativeText(log.text),
    });
  }

  /// 延迟到打开播放页时加载原生库，让初始化失败进入页面的重试流程。
  static mk.Player _newPlayer() {
    mk.MediaKit.ensureInitialized();
    return mk.Player(
      configuration: const mk.PlayerConfiguration(
        logLevel: mk.MPVLogLevel.debug,
      ),
    );
  }

  @override
  bool get playing => !_disposed && _error == null && _playing;
  @override
  bool get authenticationExpired => _authenticationExpired;
  @override
  bool get ended => _ended;
  @override
  bool get buffering => _buffering;
  @override
  String? get error => _error;
  @override
  int get positionMs => _position;
  @override
  int get durationMs => _duration;

  /// 禁用内置控件和常亮管理，统一交由现有学习页管理。
  @override
  Widget buildVideo() => _controller == null
      ? const SizedBox.shrink()
      : mv.Video(
          controller: _controller!,
          controls: mv.NoVideoControls,
          wakelock: false,
          pauseUponEnteringBackgroundMode: false,
          resumeUponEnteringForegroundMode: false,
        );

  /// 点播需等到时长可用再恢复历史位置，避免 open 返回早于媒体加载。
  @override
  Future<void> initialize(Uri uri, Map<String, String> headers) async {
    _diagnose('initialize begin', {'logLevel': 'debug'});
    final player = _player = _createPlayer();
    _controller = _createController(player);
    void changed() {
      if (!_disposed) notifyListeners();
    }

    _subscriptions.addAll([
      player.stream.log.listen(_nativeLog),
      player.stream.playing.listen((value) {
        _diagnose('playing received', {'value': value});
        _playing = value;
        changed();
      }),
      player.stream.buffering.listen((value) {
        _diagnose('buffering received', {'value': value});
        _buffering = value;
        changed();
      }),
      player.stream.position.listen((value) {
        final advanced = value.inMilliseconds > _position;
        _position = value.inMilliseconds;
        if (_elapsed.elapsedMilliseconds - _lastPositionLogMs >= 5000) {
          _lastPositionLogMs = _elapsed.elapsedMilliseconds;
          _diagnose('position sample');
        }
        // 解码回退成功后位置继续推进，清除旧错误，不能永久锁住播放状态。
        if (advanced && _playing && _error != null) {
          _diagnose('error cleared by advancing position');
          _error = null;
          _authenticationExpired = false;
          changed();
        }
        // 位置保持最新，界面最多每 250ms 刷新一次，避免逐帧重建整页。
        _positionNotification ??= Timer(const Duration(milliseconds: 250), () {
          _positionNotification = null;
          changed();
        });
      }),
      player.stream.duration.listen((value) {
        _diagnose('duration received', {'valueMs': value.inMilliseconds});
        _duration = value.inMilliseconds;
        if (_duration > 0 && !_ready.isCompleted) _ready.complete();
        changed();
      }),
      player.stream.completed.listen((value) {
        _diagnose('completed received', {'value': value});
        // completed 也可能表示异常断流；必须结合真实位置与尚未恢复的错误验证。
        // 保留原始位置，由上报层在总计两秒范围内做一次尾差校正。
        _ended =
            value &&
            _error == null &&
            _duration > 0 &&
            _position > 0 &&
            (_duration - _position).abs() <= 2000;
        if (value) {
          _playing = false;
          _buffering = false;
          if (!_ended) {
            _error ??= '播放意外中断，请重试';
            if (!_ready.isCompleted) _ready.complete();
          }
          _diagnose(_ended ? 'completed verified' : 'completed rejected');
        }
        changed();
      }),
      player.stream.error.listen((rawError) {
        _diagnose('error received', {
          'text': _safeNativeText(rawError),
          'ignored': _ended,
        });
        if (_ended) return; // 已确认结束后的尾部日志不能覆盖正常片尾状态。
        // 内核错误可能包含带认证参数的地址，不向界面或日志传递原文。
        _authenticationExpired = isPlaybackAuthenticationFailure(rawError);
        _error = '视频加载或播放失败，请重试';
        if (!_ready.isCompleted) _ready.complete();
        changed();
      }),
    ]);
    await _command(
      'open',
      () => player
          .open(mk.Media(uri.toString(), httpHeaders: headers), play: false)
          .timeout(const Duration(seconds: 25)),
    );
    if (!_disposed && _duration <= 0 && _error == null) {
      await _ready.future.timeout(const Duration(seconds: 25));
    }
    _diagnose('initialize finished');
    if (_disposed || _error != null) throw StateError('视频初始化失败');
  }

  @override
  Future<void> play() => _command('play', () async {
    await _player?.play();
  });
  @override
  Future<void> pause() => _command('pause', () async {
    await _player?.pause();
  });
  @override
  Future<void> seek(int milliseconds) async {
    _diagnose('seek requested', {'targetMs': milliseconds});
    // 新一轮跳转重新接受播放错误，不把片尾状态带到下一次播放。
    _ended = false;
    _error = null;
    _authenticationExpired = false;
    try {
      await _command('seek', () async {
        await _player?.seek(Duration(milliseconds: milliseconds));
      });
    } catch (_) {
      // 命令失败未必伴随 error 流事件，仍须阻止随后的 completed 被当作正常片尾。
      _error = '视频跳转失败，请重试';
      if (!_disposed) notifyListeners();
      rethrow;
    }
  }

  @override
  Future<void> speed(double value) async {
    _diagnose('speed requested', {'rate': value});
    await _command('speed', () async {
      await _player?.setRate(value);
    });
  }

  /// 记录命令耗时与失败，异常原样抛回既有页面处理，不改变播放行为。
  Future<void> _command(String name, Future<void> Function() action) async {
    final started = _elapsed.elapsedMilliseconds;
    _diagnose('$name begin');
    try {
      await action();
      _diagnose('$name success', {
        'costMs': _elapsed.elapsedMilliseconds - started,
      });
    } catch (error) {
      _diagnose('$name failed', {
        'costMs': _elapsed.elapsedMilliseconds - started,
        'text': _safeNativeText(error.toString()),
      });
      rethrow;
    }
  }

  /// 先停止事件投递，再异步释放原生资源，避免离页后的回调更新页面。
  @override
  void dispose() {
    if (_disposed) return;
    _diagnose('dispose', {'suppressed': _suppressed});
    _disposed = true;
    _positionNotification?.cancel();
    // 离页立即结束媒体就绪等待，不留下直到超时才结束的初始化任务。
    if (!_ready.isCompleted) _ready.complete();
    for (final subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    unawaited(_player?.dispose());
    super.dispose();
  }
}
