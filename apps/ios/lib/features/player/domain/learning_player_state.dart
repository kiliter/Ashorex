/// 播放器底层适配边界；测试使用 Fake，生产使用 video_player。
abstract interface class PlayerAdapter {
  Stream<Duration> get positionStream;

  Future<void> open(Uri uri, {Map<String, String> headers = const {}});

  Future<void> play();

  Future<void> pause();

  Future<void> seek(Duration position);

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
    this.heartbeatFailures = 0,
    this.completed = false,
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
  final int heartbeatFailures;
  final bool completed;
  final String status;

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
    int? heartbeatFailures,
    bool? completed,
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
    heartbeatFailures: heartbeatFailures ?? this.heartbeatFailures,
    completed: completed ?? this.completed,
    status: status ?? this.status,
  );
}
