import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/focus/data/focus_repository.dart';

/// 服务端计时的专注页；本地时钟只用于平滑展示，恢复前台时会重新同步服务端。
final class FocusTimerPage extends ConsumerStatefulWidget {
  const FocusTimerPage({
    required this.title,
    required this.plannedSeconds,
    this.planItemId,
    this.mediaItemId,
    super.key,
  });

  final String? planItemId;
  final String? mediaItemId;
  final String title;
  final int plannedSeconds;

  @override
  ConsumerState<FocusTimerPage> createState() => _FocusTimerPageState();
}

final class _FocusTimerPageState extends ConsumerState<FocusTimerPage>
    with WidgetsBindingObserver {
  FocusSessionData? _session;
  DateTime? _receivedAtUtc;
  Object? _error;
  Timer? _ticker;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initialize();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _ticker?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _synchronizeActive();
    }
  }

  Future<void> _initialize() async {
    try {
      final repository = ref.read(focusRepositoryProvider);
      final active = await repository.loadActive();
      final session =
          active ??
          await repository.start(
            planItemId: widget.planItemId,
            mediaItemId: widget.mediaItemId,
            focusType: 'POMODORO',
            plannedSeconds: widget.plannedSeconds,
          );
      if (active != null &&
          widget.planItemId != null &&
          active.planItemId != widget.planItemId) {
        throw StateError('已有其他专注任务正在进行，请先处理当前会话');
      }
      _accept(session);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  /// App 回到前台时读取服务端快照，避免后台时间被客户端自行计入。
  Future<void> _synchronizeActive() async {
    try {
      final active = await ref.read(focusRepositoryProvider).loadActive();
      if (active != null && active.id == _session?.id) _accept(active);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  void _accept(FocusSessionData session) {
    if (!mounted) return;
    setState(() {
      _session = session;
      _receivedAtUtc = DateTime.now().toUtc();
      _error = null;
      _busy = false;
    });
    _ticker?.cancel();
    if (session.status == 'RUNNING') {
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
        if (mounted) setState(() {});
      });
    }
  }

  int _displaySeconds(FocusSessionData session) {
    if (session.status != 'RUNNING') return session.actualSeconds;
    // actualSeconds 是 serverNow 时刻的可信累计值，本地仅从收到该快照起平滑递增。
    final localElapsed = DateTime.now()
        .toUtc()
        .difference(_receivedAtUtc ?? DateTime.now().toUtc())
        .inSeconds;
    return session.actualSeconds + (localElapsed < 0 ? 0 : localElapsed);
  }

  @override
  Widget build(BuildContext context) {
    final session = _session;
    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: SafeArea(
        child: _error != null
            ? _ErrorState(message: _error.toString(), retry: _initialize)
            : session == null
            ? const ShanganLoading('正在同步专注会话')
            : _buildSession(context, session),
      ),
    );
  }

  Widget _buildSession(BuildContext context, FocusSessionData session) {
    final actual = _displaySeconds(session);
    final remaining = (session.plannedSeconds - actual).clamp(
      0,
      session.plannedSeconds,
    );
    final running = session.status == 'RUNNING';
    final active = running || session.status == 'PAUSED';
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 36),
      children: [
        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const ShanganEyebrow('专注任务'),
                  const SizedBox(height: 6),
                  Text(
                    widget.title,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ],
              ),
            ),
            ShanganStatusTag(
              _statusLabel(session.status),
              tone: session.status == 'FINISHED'
                  ? ShanganTagTone.success
                  : running
                  ? ShanganTagTone.info
                  : ShanganTagTone.warning,
            ),
          ],
        ),
        const SizedBox(height: 16),
        _TimerDial(
          progress: session.plannedSeconds == 0
              ? 0
              : actual / session.plannedSeconds,
          actualLabel: _formatDuration(actual),
          remainingLabel: _formatDuration(remaining),
          running: running,
        ),
        ShanganMetricGrid(
          metrics: [
            (
              value: shanganDuration(actual),
              label: '已专注',
              tone: ShanganTagTone.info,
            ),
            (
              value: shanganDuration(session.plannedSeconds),
              label: '计划时长',
              tone: ShanganTagTone.success,
            ),
          ],
        ),
        const SizedBox(height: 20),
        if (active) ...[
          FilledButton.icon(
            key: Key(running ? 'pauseFocus' : 'resumeFocus'),
            onPressed: _busy
                ? null
                : () => _change(running ? 'pause' : 'resume'),
            icon: Icon(running ? Icons.pause : Icons.play_arrow),
            label: Text(running ? '暂停' : '继续'),
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            key: const Key('finishFocus'),
            onPressed: _busy ? null : () => _change('finish'),
            child: const Text('结束并结算'),
          ),
          TextButton(
            key: const Key('cancelFocus'),
            onPressed: _busy ? null : () => _change('cancel'),
            child: const Text('取消本次专注'),
          ),
        ],
      ],
    );
  }

  Future<void> _change(String action) async {
    final session = _session;
    if (session == null) return;
    setState(() => _busy = true);
    try {
      final repository = ref.read(focusRepositoryProvider);
      final next = switch (action) {
        'pause' => await repository.pause(session.id),
        'resume' => await repository.resume(session.id),
        'finish' => await repository.finish(session.id),
        'cancel' => await repository.cancel(session.id),
        _ => throw ArgumentError.value(action, 'action', '未知专注操作'),
      };
      _accept(next);
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = error;
        });
      }
    }
  }
}

/// 与原型一致的圆形计时刻度，数值仍完全来自服务端会话快照。
final class _TimerDial extends StatelessWidget {
  const _TimerDial({
    required this.progress,
    required this.actualLabel,
    required this.remainingLabel,
    required this.running,
  });

  final double progress;
  final String actualLabel;
  final String remainingLabel;
  final bool running;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 22),
    child: Center(
      child: SizedBox.square(
        dimension: 252,
        child: Stack(
          alignment: Alignment.center,
          children: [
            SizedBox.square(
              dimension: 252,
              child: CircularProgressIndicator(
                value: progress.clamp(0, 1),
                strokeWidth: 12,
                strokeCap: StrokeCap.round,
                color: ShanganColors.blue,
                backgroundColor: ShanganColors.rule.withValues(alpha: 0.4),
              ),
            ),
            Container(
              width: 210,
              height: 210,
              decoration: BoxDecoration(
                color: ShanganColors.surface,
                shape: BoxShape.circle,
                border: Border.all(color: ShanganColors.rule),
                boxShadow: const [
                  BoxShadow(
                    color: ShanganColors.blueSoft,
                    offset: Offset(5, 5),
                  ),
                ],
              ),
            ),
            Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  running ? Icons.timer_outlined : Icons.pause,
                  color: ShanganColors.blue,
                ),
                const SizedBox(height: 8),
                Text(
                  actualLabel,
                  style: shanganNumberStyle(context, fontSize: 34),
                ),
                const SizedBox(height: 5),
                Text(
                  '剩余 $remainingLabel',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}

String _formatDuration(int totalSeconds) {
  final hours = totalSeconds ~/ 3600;
  final minutes = (totalSeconds % 3600) ~/ 60;
  final seconds = totalSeconds % 60;
  return '${hours.toString().padLeft(2, '0')}:'
      '${minutes.toString().padLeft(2, '0')}:'
      '${seconds.toString().padLeft(2, '0')}';
}

String _statusLabel(String status) => switch (status) {
  'RUNNING' => '专注中',
  'PAUSED' => '已暂停',
  'FINISHED' => '已完成',
  'CANCELLED' => '已取消',
  _ => status,
};

final class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message, required this.retry});

  final String message;
  final VoidCallback retry;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.error_outline, size: 48),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center),
          const SizedBox(height: 12),
          FilledButton(onPressed: retry, child: const Text('重试')),
        ],
      ),
    ),
  );
}
