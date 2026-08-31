import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/player/presentation/alive_check_dialog.dart';
import 'package:shangan_ios/features/player/presentation/learning_player_controller.dart';
import 'package:shangan_ios/features/player/presentation/verified_progress_bar.dart';
import 'package:video_player/video_player.dart';

/// 竖屏 iOS 学习播放器：展示可信进度，并把生命周期、验活与网络失败交给控制器。
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
  bool _aliveDialogVisible = false;
  bool _networkMessageShown = false;
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
    _initialization = _controller.initialize(
      lessonId: widget.lessonId,
      planItemId: widget.planItemId,
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final foreground = state == AppLifecycleState.resumed;
    unawaited(_controller.setForeground(foreground));
  }

  void _onControllerChanged() {
    if (!mounted) return;
    setState(() {});
    final state = _controller.state;
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

  Future<void> _exit() async {
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
    _controller.removeListener(_onControllerChanged);
    unawaited(_controller.close());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = _controller.state;
    return PopScope(
      canPop: _allowPop,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(_exit());
      },
      child: Scaffold(
        appBar: AppBar(
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
              return const ShanganLoading('正在创建可信观看会话');
            }
            if (snapshot.hasError) {
              return const Center(child: Text('暂时无法创建播放会话，请稍后重试。'));
            }
            return ListView(
              padding: const EdgeInsets.only(bottom: 32),
              children: [
                Stack(
                  children: [
                    _VideoSurface(adapter: _adapter),
                    Positioned(
                      left: 12,
                      top: 10,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: ShanganColors.ink.withValues(alpha: 0.72),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: const Padding(
                          padding: EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 5,
                          ),
                          child: Row(
                            children: [
                              Icon(Icons.wifi, size: 15, color: Colors.white),
                              SizedBox(width: 5),
                              Text(
                                '服务端验证中',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 11,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
                Padding(
                  padding: const EdgeInsets.all(20),
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
                                const ShanganEyebrow('课程视频'),
                                const SizedBox(height: 6),
                                Text(
                                  widget.title,
                                  style: Theme.of(context).textTheme.titleLarge,
                                ),
                              ],
                            ),
                          ),
                          ShanganStatusTag(
                            state.isPlaying
                                ? '播放中'
                                : state.completed
                                ? '已完成'
                                : '已暂停',
                            tone: state.completed
                                ? ShanganTagTone.success
                                : state.isPlaying
                                ? ShanganTagTone.info
                                : ShanganTagTone.warning,
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                      VerifiedProgressBar(
                        duration: state.duration,
                        position: state.position,
                        maxVerifiedPosition: state.maxVerifiedPosition,
                        onSeek: _controller.seek,
                      ),
                      const SizedBox(height: 8),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          IconButton.outlined(
                            constraints: const BoxConstraints.tightFor(
                              width: 48,
                              height: 48,
                            ),
                            tooltip: '快退 10 秒',
                            onPressed: () => _controller.seek(
                              state.position - const Duration(seconds: 10),
                            ),
                            icon: const Icon(Icons.replay_10),
                          ),
                          const SizedBox(width: 18),
                          IconButton.filled(
                            constraints: const BoxConstraints.tightFor(
                              width: 58,
                              height: 58,
                            ),
                            tooltip: state.isPlaying ? '暂停' : '播放',
                            onPressed: state.completed
                                ? null
                                : state.isPlaying
                                ? _controller.pause
                                : _controller.play,
                            icon: Icon(
                              state.isPlaying ? Icons.pause : Icons.play_arrow,
                            ),
                          ),
                          const SizedBox(width: 18),
                          IconButton.outlined(
                            constraints: const BoxConstraints.tightFor(
                              width: 48,
                              height: 48,
                            ),
                            tooltip: '快进 10 秒',
                            onPressed: () => _controller.seek(
                              state.position + const Duration(seconds: 10),
                            ),
                            icon: const Icon(Icons.forward_10),
                          ),
                        ],
                      ),
                      const SizedBox(height: 18),
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
                            value: state.completed ? '已达到' : '未达到',
                            label: '完成阈值',
                            tone: state.completed
                                ? ShanganTagTone.success
                                : ShanganTagTone.warning,
                          ),
                          (
                            value: _clock(state.duration),
                            label: '总时长',
                            tone: ShanganTagTone.risk,
                          ),
                        ],
                      ),
                      const SizedBox(height: 18),
                      if (state.completed) ...[
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
                                if (widget.planItemId != null)
                                  'planItemId': widget.planItemId!,
                              },
                            ).toString(),
                          ),
                          icon: const Icon(Icons.quiz_outlined),
                          label: const Text('开始课后答题'),
                        ),
                      ],
                      if (state.networkError)
                        OutlinedButton.icon(
                          onPressed: _controller.play,
                          icon: const Icon(Icons.refresh),
                          label: const Text('网络恢复后继续'),
                        ),
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
}

String _clock(Duration value) {
  final hours = value.inHours;
  final minutes = value.inMinutes.remainder(60);
  final seconds = value.inSeconds.remainder(60);
  return hours > 0
      ? '${hours.toString().padLeft(2, '0')}:${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}'
      : '${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
}

/// 生产视频画面只读取适配器持有的官方 VideoPlayerController。
final class _VideoSurface extends StatelessWidget {
  const _VideoSurface({required this.adapter});

  final VideoPlayerAdapter adapter;

  @override
  Widget build(BuildContext context) {
    final controller = adapter.videoController;
    if (controller == null || !controller.value.isInitialized) {
      return const AspectRatio(
        aspectRatio: 16 / 9,
        child: ColoredBox(
          color: Colors.black,
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }
    return ColoredBox(
      color: Colors.black,
      child: AspectRatio(
        aspectRatio: controller.value.aspectRatio == 0
            ? 16 / 9
            : controller.value.aspectRatio,
        child: VideoPlayer(controller),
      ),
    );
  }
}
