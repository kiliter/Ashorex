import 'package:flutter/material.dart';
import 'package:shangan_ios/core/player/media_kit_playback_adapter.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:video_player/video_player.dart';

/// 播放平台边界：页面读取真实状态，测试使用假适配器而非模拟业务计时。
abstract class PlaybackAdapter extends ChangeNotifier {
  bool get playing;

  /// 原生媒体层明确报告 401 时为真，页面据此刷新凭据并重开媒体源。
  bool get authenticationExpired => false;

  /// 只有原生内核明确报告片尾才为真，不用四舍五入后的时间推断。
  bool get ended => false;
  bool get buffering;
  String? get error;
  int get positionMs;
  int get durationMs;
  Widget buildVideo();
  Future<void> initialize(Uri uri, Map<String, String> headers);
  Future<void> play();
  Future<void> pause();
  Future<void> seek(int milliseconds);
  Future<void> speed(double value);
}

/// 原生播放器错误文本没有统一结构，只在明确出现 HTTP 401/Unauthorized 时触发凭据重开。
///
/// 其他网络、解码或 403 业务拒绝仍进入普通手动重试，避免自动重试死循环。
bool isPlaybackAuthenticationFailure(String? error) {
  if (error == null) return false;
  final normalized = error.toLowerCase();
  return normalized.contains('unauthorized') ||
      RegExp(r'(^|\D)401(\D|$)').hasMatch(normalized);
}

/// 官方 video_player 接管解码、流式缓冲、跳转与倍速。
final class NativePlaybackAdapter extends PlaybackAdapter {
  VideoPlayerController? _controller;
  static Future<void> _releaseBarrier = Future<void>.value();
  bool _disposed = false;
  @override
  bool get playing => _controller?.value.isPlaying ?? false;
  @override
  bool get authenticationExpired =>
      isPlaybackAuthenticationFailure(_controller?.value.errorDescription);
  @override
  bool get ended => _controller?.value.isCompleted ?? false;
  @override
  bool get buffering => _controller?.value.isBuffering ?? false;
  @override
  String? get error =>
      _controller?.value.hasError == true ? '视频加载或播放失败，请检查网络后重试' : null;
  @override
  int get positionMs => _controller?.value.position.inMilliseconds ?? 0;
  @override
  int get durationMs => _controller?.value.duration.inMilliseconds ?? 0;
  @override
  Widget buildVideo() {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      return const SizedBox.shrink();
    }
    return Center(
      child: AspectRatio(
        aspectRatio: controller.value.aspectRatio,
        child: VideoPlayer(controller),
      ),
    );
  }

  @override
  Future<void> initialize(Uri uri, Map<String, String> headers) async {
    // 快速重进必须等待旧 AVPlayer 释放，避免原生会话交叠。
    // 原生释放事件丢失时不让后续所有播放页永久排队。
    await _releaseBarrier.timeout(const Duration(seconds: 5), onTimeout: () {});
    if (_disposed) throw StateError('播放器已关闭');
    final controller = VideoPlayerController.networkUrl(
      uri,
      httpHeaders: headers,
      // iOS 使用原生 AVPlayerLayer，绕开模拟器的视频纹理转换路径。
      // Android 保留纹理模式，避免扩大平台视图兼容性改动。
      viewType: !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS
          ? VideoViewType.platformView
          : VideoViewType.textureView,
    );
    _controller = controller;
    controller.addListener(notifyListeners);
    await controller.initialize().timeout(const Duration(seconds: 25));
    if (_disposed) throw StateError('播放器已关闭');
  }

  @override
  Future<void> play() async => _controller?.play();
  @override
  Future<void> pause() async => _controller?.pause();
  @override
  Future<void> seek(int milliseconds) async =>
      _controller?.seekTo(Duration(milliseconds: milliseconds));
  @override
  Future<void> speed(double value) async =>
      _controller?.setPlaybackSpeed(value);
  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _controller?.removeListener(notifyListeners);
    // 释放失败不能污染静态等待链；页面加载仍有独立的总超时。
    _releaseBarrier = (_controller?.dispose() ?? Future<void>.value())
        .timeout(const Duration(seconds: 5), onTimeout: () {})
        .catchError((Object _) {});
    super.dispose();
  }
}

final playbackAdapterFactoryProvider = Provider<PlaybackAdapter Function()>(
  // iOS 试用发现模拟器音频和稳定性问题，恢复 AVPlayer 原生视图。
  // 显式构建参数仍可选择 media_kit 做同源对照，其他平台维持试用。
  (ref) {
    const engine = String.fromEnvironment('PLAYBACK_ENGINE');
    if (engine == 'media_kit') {
      debugPrint('播放器内核：media_kit（显式选择）');
      return MediaKitPlaybackAdapter.new;
    }
    if (engine == 'video_player' ||
        defaultTargetPlatform == TargetPlatform.iOS) {
      debugPrint('播放器内核：video_player / AVPlayer（iOS 使用原生视图）');
      return NativePlaybackAdapter.new;
    }
    debugPrint('播放器内核：media_kit');
    return MediaKitPlaybackAdapter.new;
  },
);

/// 单调时钟用于观看时长，避免系统校时与倍速改变累计口径；测试可注入假时钟。
final playbackTimeProvider = Provider<int Function()>((ref) {
  final clock = Stopwatch()..start();
  return () => clock.elapsedMilliseconds;
});
