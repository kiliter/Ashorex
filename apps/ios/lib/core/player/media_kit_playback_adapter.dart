import 'dart:async';

import 'package:flutter/material.dart';
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
  bool _playing = false;
  bool _ended = false;
  bool _buffering = false;
  int _position = 0;
  int _duration = 0;
  String? _error;
  final _ready = Completer<void>();
  Timer? _positionNotification;

  /// 延迟到打开播放页时加载原生库，让初始化失败进入页面的重试流程。
  static mk.Player _newPlayer() {
    mk.MediaKit.ensureInitialized();
    return mk.Player(
      configuration: const mk.PlayerConfiguration(
        logLevel: mk.MPVLogLevel.error,
      ),
    );
  }

  @override
  bool get playing => !_disposed && _error == null && _playing;
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
    final player = _player = _createPlayer();
    _controller = _createController(player);
    void changed() {
      if (!_disposed) notifyListeners();
    }

    _subscriptions.addAll([
      player.stream.playing.listen((value) {
        _playing = value;
        changed();
      }),
      player.stream.buffering.listen((value) {
        _buffering = value;
        changed();
      }),
      player.stream.position.listen((value) {
        final advanced = value.inMilliseconds > _position;
        _position = value.inMilliseconds;
        // 解码回退成功后位置继续推进，清除旧错误，不能永久锁住播放状态。
        if (advanced && _playing && _error != null) {
          _error = null;
          changed();
        }
        // 位置保持最新，界面最多每 250ms 刷新一次，避免逐帧重建整页。
        _positionNotification ??= Timer(const Duration(milliseconds: 250), () {
          _positionNotification = null;
          changed();
        });
      }),
      player.stream.duration.listen((value) {
        _duration = value.inMilliseconds;
        if (_duration > 0 && !_ready.isCompleted) _ready.complete();
        changed();
      }),
      player.stream.completed.listen((value) {
        _ended = value;
        if (value) {
          // 内核确认片尾后对齐最终位置，清除 EOF 附带错误并通知页面补报。
          _playing = false;
          _buffering = false;
          _error = null;
          _position = _duration;
        }
        changed();
      }),
      player.stream.error.listen((_) {
        if (_ended) return; // 已确认结束后的尾部日志不能覆盖正常片尾状态。
        // 内核错误可能包含带认证参数的地址，不向界面或日志传递原文。
        _error = '视频加载或播放失败，请重试';
        if (!_ready.isCompleted) _ready.complete();
        changed();
      }),
    ]);
    await player
        .open(mk.Media(uri.toString(), httpHeaders: headers), play: false)
        .timeout(const Duration(seconds: 25));
    if (!_disposed && _duration <= 0 && _error == null) {
      await _ready.future.timeout(const Duration(seconds: 25));
    }
    if (_disposed || _error != null) throw StateError('视频初始化失败');
  }

  @override
  Future<void> play() async => _player?.play();
  @override
  Future<void> pause() async => _player?.pause();
  @override
  Future<void> seek(int milliseconds) async {
    // 新一轮跳转重新接受播放错误，不把片尾状态带到下一次播放。
    _ended = false;
    _error = null;
    await _player?.seek(Duration(milliseconds: milliseconds));
  }

  @override
  Future<void> speed(double value) async => _player?.setRate(value);

  /// 先停止事件投递，再异步释放原生资源，避免离页后的回调更新页面。
  @override
  void dispose() {
    if (_disposed) return;
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
