import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_markdown.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/player/domain/learning_player_state.dart';
import 'package:shangan_ios/features/player/presentation/alive_check_dialog.dart';
import 'package:shangan_ios/features/player/presentation/learning_player_controller.dart';
import 'package:video_player/video_player.dart';

/// iOS 学习播放器进入页面时只读课时信息，首次点击播放后才创建可信观看会话。
final class LearningPlayerPage extends ConsumerStatefulWidget {
  const LearningPlayerPage({
    required this.lessonId,
    required this.title,
    this.planItemId,
    super.key,
  });

  final String lessonId;
  final String? planItemId;
  final String title;

  @override
  ConsumerState<LearningPlayerPage> createState() => _LearningPlayerPageState();
}

final class _LearningPlayerPageState extends ConsumerState<LearningPlayerPage>
    with WidgetsBindingObserver {
  late final VideoPlayerAdapter _adapter;
  late final LearningPlayerController _controller;
  late final Future<void> _initialization;
  late final Future<LessonStudyContentData> _studyContent;
  Timer? _controlsTimer;
  bool _controlsVisible = true;
  bool _lastPlaying = false;
  bool _fullscreen = false;
  bool _aliveDialogVisible = false;
  bool _networkMessageShown = false;
  bool _playbackMessageShown = false;
  bool _allowPop = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _adapter = VideoPlayerAdapter();
    _controller = LearningPlayerController(
      repository: ref.read(watchRepositoryProvider),
      player: _adapter,
    )..addListener(_onControllerChanged);
    _initialization = _loadLessonMetadata();
    // 摘要和播放会话并行加载；摘要不可用时页面直接隐藏该区域。
    _studyContent = ref
        .read(catalogRepositoryProvider)
        .loadStudyContent(widget.lessonId);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final foreground = state == AppLifecycleState.resumed;
    unawaited(_controller.setForeground(foreground));
  }

  void _onControllerChanged() {
    if (!mounted) return;
    final state = _controller.state;
    setState(() {});
    if (state.isPlaying && !_lastPlaying) _scheduleControlsHide();
    _lastPlaying = state.isPlaying;
    if (state.aliveCheckRequired && !_aliveDialogVisible) {
      _aliveDialogVisible = true;
      WidgetsBinding.instance.addPostFrameCallback((_) => _showAliveCheck());
    }
    if (state.networkError && !_networkMessageShown) {
      _networkMessageShown = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('连续三次心跳失败，视频已暂停。请检查网络后重试。')),
        );
      });
    }
    if (!state.networkError) _networkMessageShown = false;
    if (state.playbackStartError && !_playbackMessageShown) {
      _playbackMessageShown = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        final messenger = ScaffoldMessenger.of(context);
        messenger.clearSnackBars();
        messenger.showSnackBar(
          const SnackBar(content: Text('视频准备失败，请检查服务后重试。')),
        );
      });
    }
    if (!state.playbackStartError) _playbackMessageShown = false;
  }

  /// 只读取课时时长和历史可信进度；此阶段不会创建会话或锁定作战单项目。
  Future<void> _loadLessonMetadata() async {
    final lesson = await ref
        .read(catalogRepositoryProvider)
        .loadLesson(widget.lessonId);
    await _controller.initialize(
      lessonId: widget.lessonId,
      planItemId: widget.planItemId,
      duration: Duration(milliseconds: lesson.durationMs),
      trustedPosition: Duration(milliseconds: lesson.maxVerifiedPositionMs),
    );
  }

  void _toggleControls() {
    setState(() => _controlsVisible = !_controlsVisible);
    if (_controlsVisible) _scheduleControlsHide();
  }

  void _revealControls() {
    if (!_controlsVisible) setState(() => _controlsVisible = true);
    _scheduleControlsHide();
  }

  void _scheduleControlsHide() {
    _controlsTimer?.cancel();
    if (!_controller.state.isPlaying) return;
    _controlsTimer = Timer(const Duration(seconds: 3), () {
      if (mounted && _controller.state.isPlaying) {
        setState(() => _controlsVisible = false);
      }
    });
  }

  Future<void> _showAliveCheck() async {
    if (!mounted || !_controller.state.aliveCheckRequired) {
      _aliveDialogVisible = false;
      return;
    }
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) =>
          AliveCheckDialog(onConfirm: _controller.confirmAliveCheck),
    );
    _aliveDialogVisible = false;
  }

  Future<void> _toggleFullscreen() async {
    if (_fullscreen) {
      await _leaveFullscreen();
      return;
    }
    await SystemChrome.setPreferredOrientations(const [
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    if (mounted) {
      setState(() {
        _fullscreen = true;
        _controlsVisible = true;
      });
      _scheduleControlsHide();
    }
  }

  Future<void> _leaveFullscreen() async {
    await _restorePortraitUi();
    if (mounted) {
      setState(() {
        _fullscreen = false;
        _controlsVisible = true;
      });
    }
  }

  Future<void> _restorePortraitUi() async {
    await SystemChrome.setPreferredOrientations(const [
      DeviceOrientation.portraitUp,
    ]);
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  }

  Future<void> _exit() async {
    if (_fullscreen) {
      await _leaveFullscreen();
      return;
    }
    if (_allowPop) return;
    try {
      await _controller.stop();
    } finally {
      if (mounted) {
        setState(() => _allowPop = true);
        context.pop();
      }
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controlsTimer?.cancel();
    _controller.removeListener(_onControllerChanged);
    unawaited(_controller.close());
    if (_fullscreen) unawaited(_restorePortraitUi());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = _controller.state;
    final watchedPercent = state.trustedWatchedPercent;
    final watchedDone = state.trustedWatchDone;
    return PopScope(
      canPop: _allowPop,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(_exit());
      },
      child: Scaffold(
        backgroundColor: _fullscreen ? Colors.black : null,
        appBar: _fullscreen
            ? null
            : AppBar(
                leading: IconButton(
                  tooltip: '返回并结束学习',
                  onPressed: _exit,
                  icon: const Icon(Icons.arrow_back_ios_new),
                ),
                title: const Text('可信学习'),
              ),
        body: FutureBuilder<void>(
          future: _initialization,
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const ShanganLoading('正在读取课时信息');
            }
            if (snapshot.hasError) {
              return const Center(child: Text('课时信息加载失败，请稍后重试。'));
            }
            final stage = _PlayerStage(
              adapter: _adapter,
              state: state,
              fullscreen: _fullscreen,
              controlsVisible: _controlsVisible,
              onToggleControls: _toggleControls,
              onInteraction: _revealControls,
              onBack: _leaveFullscreen,
              onFullscreen: _toggleFullscreen,
              onSeek: _controller.seek,
              onCyclePlaybackSpeed: _controller.cyclePlaybackSpeed,
              onPlayPause: state.isPlaying
                  ? _controller.pause
                  : _controller.play,
            );
            if (_fullscreen) return stage;
            return ListView(
              padding: const EdgeInsets.only(bottom: 32),
              children: [
                AspectRatio(aspectRatio: 16 / 9, child: stage),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                ShanganEyebrow(
                                  state.reviewMode ? '复习快捷入口' : '课程视频',
                                ),
                                const SizedBox(height: 6),
                                Text(
                                  widget.title,
                                  style: Theme.of(context).textTheme.titleLarge,
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 10),
                      ShanganWatchProgress(
                        progressPercent: watchedPercent,
                        completed: watchedDone,
                      ),
                      const SizedBox(height: 16),
                      if (state.reviewMode)
                        const ShanganNotice(
                          title: '本次播放仅记为复习审计',
                          message: '可以自由拖动，不计入学习进度、完成率或欠债。',
                          tone: ShanganTagTone.success,
                        )
                      else
                        ShanganMetricGrid(
                          metrics: [
                            (
                              value: _clock(state.position),
                              label: '当前播放',
                              tone: ShanganTagTone.info,
                            ),
                            (
                              value: _clock(state.maxVerifiedPosition),
                              label: '可信最大位置',
                              tone: ShanganTagTone.success,
                            ),
                            (
                              value: watchedDone ? '已看完' : '$watchedPercent%',
                              label: '已观看比例',
                              tone: watchedDone
                                  ? ShanganTagTone.success
                                  : watchedPercent > 0
                                  ? ShanganTagTone.info
                                  : ShanganTagTone.neutral,
                            ),
                            (
                              value: _clock(state.duration),
                              label: '总时长',
                              tone: ShanganTagTone.risk,
                            ),
                          ],
                        ),
                      if (state.completed && !state.reviewMode) ...[
                        const SizedBox(height: 18),
                        const ShanganNotice(
                          title: '已达到可信观看完成阈值',
                          message: '若本课配置了题目，提交完整答卷后计划任务才会完成。',
                          tone: ShanganTagTone.success,
                        ),
                        const SizedBox(height: 12),
                        FilledButton.icon(
                          onPressed: () => context.push(
                            Uri(
                              path: '/quiz/${widget.lessonId}',
                              queryParameters: {
                                'planItemId': ?widget.planItemId,
                              },
                            ).toString(),
                          ),
                          icon: const Icon(Icons.quiz_outlined),
                          label: const Text('开始课后答题'),
                        ),
                      ],
                      if (state.networkError) ...[
                        const SizedBox(height: 16),
                        OutlinedButton.icon(
                          onPressed: _controller.play,
                          icon: const Icon(Icons.refresh),
                          label: const Text('网络恢复后继续'),
                        ),
                      ],
                      _buildSummary(),
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  /// 只有服务端明确返回 READY 且摘要非空时，播放器下方才出现摘要。
  Widget _buildSummary() {
    return FutureBuilder<LessonStudyContentData>(
      future: _studyContent,
      builder: (context, snapshot) {
        final content = snapshot.data;
        final summary = content?.summaryMarkdown?.trim();
        if (content?.summaryStatus != 'READY' ||
            summary == null ||
            summary.isEmpty) {
          return const SizedBox.shrink();
        }
        return Padding(
          padding: const EdgeInsets.only(top: 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Divider(color: ShanganColors.ink, thickness: 2),
              const SizedBox(height: 16),
              const ShanganEyebrow('AI 识别摘要'),
              const SizedBox(height: 8),
              ShanganSurface(child: ShanganMarkdown(data: summary)),
            ],
          ),
        );
      },
    );
  }
}

/// 播放画面、可信进度和所有控制按钮组成一个不可分割的播放器区域。
final class _PlayerStage extends StatelessWidget {
  const _PlayerStage({
    required this.adapter,
    required this.state,
    required this.fullscreen,
    required this.controlsVisible,
    required this.onToggleControls,
    required this.onInteraction,
    required this.onBack,
    required this.onFullscreen,
    required this.onSeek,
    required this.onCyclePlaybackSpeed,
    required this.onPlayPause,
  });

  final VideoPlayerAdapter adapter;
  final LearningPlayerState state;
  final bool fullscreen;
  final bool controlsVisible;
  final VoidCallback onToggleControls;
  final VoidCallback onInteraction;
  final VoidCallback onBack;
  final VoidCallback onFullscreen;
  final Future<void> Function(Duration) onSeek;
  final Future<void> Function() onCyclePlaybackSpeed;
  final Future<void> Function() onPlayPause;

  @override
  Widget build(BuildContext context) {
    final maximum = state.reviewMode
        ? state.duration
        : state.maxVerifiedPosition;
    return ColoredBox(
      color: Colors.black,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onToggleControls,
        child: Stack(
          fit: StackFit.expand,
          children: [
            _VideoSurface(
              adapter: adapter,
              fullscreen: fullscreen,
              preparingPlayback: state.preparingPlayback,
            ),
            IgnorePointer(
              ignoring: !controlsVisible,
              child: AnimatedOpacity(
                opacity: controlsVisible ? 1 : 0,
                duration: const Duration(milliseconds: 180),
                child: DecoratedBox(
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [
                        Color(0xB8000000),
                        Color(0x1A000000),
                        Color(0xC9000000),
                      ],
                    ),
                  ),
                  child: SafeArea(
                    left: fullscreen,
                    right: fullscreen,
                    top: fullscreen,
                    bottom: fullscreen,
                    child: Padding(
                      padding: EdgeInsets.fromLTRB(
                        fullscreen ? 18 : 10,
                        fullscreen ? 8 : 4,
                        fullscreen ? 18 : 10,
                        fullscreen ? 10 : 4,
                      ),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              if (fullscreen)
                                _VideoIconButton(
                                  tooltip: '退出全屏',
                                  icon: Icons.arrow_back_ios_new,
                                  onPressed: onBack,
                                ),
                              DecoratedBox(
                                decoration: BoxDecoration(
                                  color: Colors.black.withValues(alpha: 0.45),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 8,
                                    vertical: 5,
                                  ),
                                  child: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const Icon(
                                        Icons.verified_user_outlined,
                                        size: 15,
                                        color: Colors.white,
                                      ),
                                      const SizedBox(width: 5),
                                      Text(
                                        _sessionLabel(state),
                                        style: const TextStyle(
                                          color: Colors.white,
                                          fontSize: 11,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                              const Spacer(),
                              _PlaybackSpeedButton(
                                speed: state.playbackSpeed,
                                onPressed: () {
                                  onInteraction();
                                  onCyclePlaybackSpeed();
                                },
                              ),
                              const SizedBox(width: 4),
                              _VideoIconButton(
                                tooltip: fullscreen ? '退出全屏' : '横屏全屏',
                                icon: fullscreen
                                    ? Icons.fullscreen_exit
                                    : Icons.fullscreen,
                                onPressed: onFullscreen,
                              ),
                            ],
                          ),
                          const Spacer(),
                          SizedBox(
                            width: fullscreen ? 320 : 260,
                            height: fullscreen ? 76 : 64,
                            child: Stack(
                              alignment: Alignment.center,
                              children: [
                                Align(
                                  alignment: const Alignment(-0.78, 0),
                                  child: _VideoIconButton(
                                    tooltip: '快退 10 秒',
                                    icon: Icons.replay_10,
                                    size: fullscreen ? 34 : 28,
                                    onPressed: () {
                                      onInteraction();
                                      onSeek(
                                        state.position -
                                            const Duration(seconds: 10),
                                      );
                                    },
                                  ),
                                ),
                                Align(
                                  child: state.preparingPlayback
                                      ? SizedBox.square(
                                          dimension: fullscreen ? 52 : 44,
                                          child:
                                              const CircularProgressIndicator(
                                                color: Colors.white,
                                                strokeWidth: 3,
                                              ),
                                        )
                                      : _VideoIconButton(
                                          tooltip: state.isPlaying
                                              ? '暂停'
                                              : '播放',
                                          icon: state.isPlaying
                                              ? Icons.pause_circle_filled
                                              : Icons.play_circle_fill,
                                          size: fullscreen ? 64 : 52,
                                          buttonSize: fullscreen ? 72 : 64,
                                          onPressed:
                                              state.completed &&
                                                  !state.reviewMode
                                              ? null
                                              : () {
                                                  onInteraction();
                                                  onPlayPause();
                                                },
                                        ),
                                ),
                                Align(
                                  alignment: const Alignment(0.78, 0),
                                  child: _VideoIconButton(
                                    tooltip: '快进 10 秒',
                                    icon: Icons.forward_10,
                                    size: fullscreen ? 34 : 28,
                                    onPressed: () {
                                      onInteraction();
                                      onSeek(
                                        state.position +
                                            const Duration(seconds: 10),
                                      );
                                    },
                                  ),
                                ),
                              ],
                            ),
                          ),
                          const Spacer(),
                          Row(
                            children: [
                              Text(
                                _clock(state.position),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontFeatures: [FontFeature.tabularFigures()],
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: _OverlayTimeline(
                                  duration: state.duration,
                                  position: state.position,
                                  maximumSeek: maximum,
                                  onInteraction: onInteraction,
                                  onSeek: onSeek,
                                ),
                              ),
                              const SizedBox(width: 8),
                              Text(
                                _clock(state.duration),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontFeatures: [FontFeature.tabularFigures()],
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

final class _OverlayTimeline extends StatefulWidget {
  const _OverlayTimeline({
    required this.duration,
    required this.position,
    required this.maximumSeek,
    required this.onInteraction,
    required this.onSeek,
  });

  final Duration duration;
  final Duration position;
  final Duration maximumSeek;
  final VoidCallback onInteraction;
  final Future<void> Function(Duration) onSeek;

  @override
  State<_OverlayTimeline> createState() => _OverlayTimelineState();
}

final class _OverlayTimelineState extends State<_OverlayTimeline> {
  double? _dragValue;

  @override
  Widget build(BuildContext context) {
    final durationMs = widget.duration.inMilliseconds.toDouble().clamp(
      1.0,
      double.infinity,
    );
    final maximumMs = widget.maximumSeek.inMilliseconds.toDouble().clamp(
      0.0,
      durationMs,
    );
    final value = (_dragValue ?? widget.position.inMilliseconds.toDouble())
        .clamp(0.0, maximumMs);
    return Semantics(
      label: '视频进度',
      value: '${_clock(widget.position)} / ${_clock(widget.duration)}',
      child: SizedBox(
        height: 44,
        child: SliderTheme(
          data: SliderTheme.of(context).copyWith(
            activeTrackColor: Colors.white,
            inactiveTrackColor: Colors.white38,
            secondaryActiveTrackColor: ShanganColors.blue,
            thumbColor: Colors.white,
            overlayColor: Colors.white24,
            trackHeight: 3,
          ),
          child: Slider(
            value: value,
            secondaryTrackValue: maximumMs,
            min: 0,
            max: durationMs,
            onChangeStart: (_) => widget.onInteraction(),
            onChanged: (candidate) {
              setState(() => _dragValue = candidate.clamp(0.0, maximumMs));
            },
            onChangeEnd: (candidate) {
              final safe = candidate.clamp(0.0, maximumMs).round();
              setState(() => _dragValue = null);
              widget.onSeek(Duration(milliseconds: safe));
            },
          ),
        ),
      ),
    );
  }
}

/// 视频内按钮统一保证至少 44pt 点击区域。
final class _VideoIconButton extends StatelessWidget {
  const _VideoIconButton({
    required this.tooltip,
    required this.icon,
    required this.onPressed,
    this.size = 24,
    this.buttonSize = 48,
  });

  final String tooltip;
  final IconData icon;
  final VoidCallback? onPressed;
  final double size;
  final double buttonSize;

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: tooltip,
    constraints: BoxConstraints.tightFor(width: buttonSize, height: buttonSize),
    onPressed: onPressed,
    icon: Icon(icon, color: Colors.white, size: size),
  );
}

/// 倍速按钮直接显示当前值，点击后按 1.0、1.25、1.5、2.0 倍循环。
final class _PlaybackSpeedButton extends StatelessWidget {
  const _PlaybackSpeedButton({required this.speed, required this.onPressed});

  final double speed;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 48,
    child: TextButton(
      style: TextButton.styleFrom(
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        minimumSize: const Size(48, 48),
      ),
      onPressed: onPressed,
      child: Text(
        speed == 1.25 ? '1.25x' : '${speed.toStringAsFixed(1)}x',
        style: const TextStyle(fontWeight: FontWeight.w700),
      ),
    ),
  );
}

/// 生产视频画面只读取适配器持有的官方 VideoPlayerController。
final class _VideoSurface extends StatelessWidget {
  const _VideoSurface({
    required this.adapter,
    required this.fullscreen,
    required this.preparingPlayback,
  });

  final VideoPlayerAdapter adapter;
  final bool fullscreen;
  final bool preparingPlayback;

  @override
  Widget build(BuildContext context) {
    final controller = adapter.videoController;
    if (controller == null || !controller.value.isInitialized) {
      return ColoredBox(
        color: Colors.black,
        child: preparingPlayback
            ? const Center(child: CircularProgressIndicator())
            : const SizedBox.expand(),
      );
    }
    final ratio = controller.value.aspectRatio == 0
        ? 16 / 9
        : controller.value.aspectRatio;
    return ColoredBox(
      color: Colors.black,
      child: Center(
        child: fullscreen
            ? AspectRatio(aspectRatio: ratio, child: VideoPlayer(controller))
            : SizedBox.expand(child: VideoPlayer(controller)),
      ),
    );
  }
}

/// 播放器左上角明确提示“尚未开始”，避免进入页面就被误认为已经锁定。
String _sessionLabel(LearningPlayerState state) {
  if (state.preparingPlayback) return '正在准备视频';
  if (state.sessionId == null) return '点击播放后开始';
  return state.reviewMode ? '复习审计' : '服务端验证中';
}

String _clock(Duration value) {
  final hours = value.inHours;
  final minutes = value.inMinutes.remainder(60);
  final seconds = value.inSeconds.remainder(60);
  return hours > 0
      ? '${hours.toString().padLeft(2, '0')}:'
            '${minutes.toString().padLeft(2, '0')}:'
            '${seconds.toString().padLeft(2, '0')}'
      : '${minutes.toString().padLeft(2, '0')}:'
            '${seconds.toString().padLeft(2, '0')}';
}
