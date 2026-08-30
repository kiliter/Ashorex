import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/features/ai_chat/presentation/video_ai_sheet.dart';
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
          title: Text(widget.title),
        ),
        body: FutureBuilder<void>(
          future: _initialization,
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return const Center(child: Text('暂时无法创建播放会话，请稍后重试。'));
            }
            return ListView(
              padding: const EdgeInsets.only(bottom: 32),
              children: [
                _VideoSurface(adapter: _adapter),
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        widget.title,
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 12),
                      VerifiedProgressBar(
                        duration: state.duration,
                        position: state.position,
                        maxVerifiedPosition: state.maxVerifiedPosition,
                        onSeek: _controller.seek,
                      ),
                      const SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          IconButton.filledTonal(
                            constraints: const BoxConstraints.tightFor(
                              width: 52,
                              height: 52,
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
                        ],
                      ),
                      const SizedBox(height: 12),
                      if (state.completed) ...[
                        const ListTile(
                          leading: Icon(Icons.check_circle_outline),
                          title: Text('已达到可信观看完成阈值'),
                        ),
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
                        FilledButton.tonalIcon(
                          onPressed: _controller.play,
                          icon: const Icon(Icons.refresh),
                          label: const Text('网络恢复后继续'),
                        ),
                      OutlinedButton.icon(
                        onPressed: () => showModalBottomSheet<void>(
                          context: context,
                          isScrollControlled: true,
                          useSafeArea: true,
                          builder: (_) => VideoAiSheet(
                            lessonId: widget.lessonId,
                            currentPosition: state.position,
                            onVideoSeek: (position) {
                              Navigator.of(context).pop();
                              unawaited(_controller.seek(position));
                            },
                          ),
                        ),
                        icon: const Icon(Icons.auto_awesome_outlined),
                        label: const Text('问问视频 AI'),
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
