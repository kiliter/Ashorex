/// 播放器底层适配边界；测试使用 Fake，生产使用 video_player。
abstract interface class PlayerAdapter {
  Stream<Duration> get positionStream;

  Future<void> open(Uri uri, {Map<String, String> headers = const {}});

  Future<void> play();

  Future<void> pause();

  Future<void> seek(Duration position);

  /// 调整底层播放器倍速；可信观看仍由服务端心跳独立校验。
  Future<void> setPlaybackSpeed(double speed);

  Future<void> dispose();
}

/// iOS 学习播放器只保存展示和设备控制状态，可信真相始终来自服务端。
final class LearningPlayerState {
  const LearningPlayerState({
    this.sessionId,
    this.ticketUri,
    this.duration = Duration.zero,
    this.position = Duration.zero,
    this.maxVerifiedPosition = Duration.zero,
    this.initialized = false,
    this.isPlaying = false,
    this.isForeground = true,
    this.aliveCheckRequired = false,
    this.networkError = false,
    this.preparingPlayback = false,
    this.playbackStartError = false,
    this.heartbeatFailures = 0,
    this.completed = false,
    this.reviewMode = false,
    this.playbackSpeed = 1.0,
    this.status = 'INITIALIZING',
  });

  final String? sessionId;
  final Uri? ticketUri;
  final Duration duration;
  final Duration position;
  final Duration maxVerifiedPosition;
  final bool initialized;
  final bool isPlaying;
  final bool isForeground;
  final bool aliveCheckRequired;
  final bool networkError;
  final bool preparingPlayback;
  final bool playbackStartError;
  final int heartbeatFailures;
  final bool completed;
  final bool reviewMode;
  final double playbackSpeed;
  final String status;

  /// 按可信最大位置计算观看百分比，达到完成阈值后统一显示 100%。
  int get trustedWatchedPercent {
    if (trustedWatchDone) return 100;
    final durationMs = duration.inMilliseconds;
    if (durationMs <= 0) return 0;
    return (maxVerifiedPosition.inMilliseconds * 100 ~/ durationMs).clamp(
      0,
      99,
    );
  }

  /// 与服务端规则一致：距离结尾不超过 30 秒或总时长 2% 即视为已看完。
  bool get trustedWatchDone {
    if (completed) return true;
    final durationMs = duration.inMilliseconds;
    if (durationMs <= 0) return false;
    final allowanceMs = (durationMs * 0.02).round().clamp(0, 30000);
    return maxVerifiedPosition.inMilliseconds >= durationMs - allowanceMs;
  }

  LearningPlayerState copyWith({
    String? sessionId,
    Uri? ticketUri,
    Duration? duration,
    Duration? position,
    Duration? maxVerifiedPosition,
    bool? initialized,
    bool? isPlaying,
    bool? isForeground,
    bool? aliveCheckRequired,
    bool? networkError,
    bool? preparingPlayback,
    bool? playbackStartError,
    int? heartbeatFailures,
    bool? completed,
    bool? reviewMode,
    double? playbackSpeed,
    String? status,
  }) => LearningPlayerState(
    sessionId: sessionId ?? this.sessionId,
    ticketUri: ticketUri ?? this.ticketUri,
    duration: duration ?? this.duration,
    position: position ?? this.position,
    maxVerifiedPosition: maxVerifiedPosition ?? this.maxVerifiedPosition,
    initialized: initialized ?? this.initialized,
    isPlaying: isPlaying ?? this.isPlaying,
    isForeground: isForeground ?? this.isForeground,
    aliveCheckRequired: aliveCheckRequired ?? this.aliveCheckRequired,
    networkError: networkError ?? this.networkError,
    preparingPlayback: preparingPlayback ?? this.preparingPlayback,
    playbackStartError: playbackStartError ?? this.playbackStartError,
    heartbeatFailures: heartbeatFailures ?? this.heartbeatFailures,
    completed: completed ?? this.completed,
    reviewMode: reviewMode ?? this.reviewMode,
    playbackSpeed: playbackSpeed ?? this.playbackSpeed,
    status: status ?? this.status,
  );
}
