import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/focus/data/focus_repository.dart';
import 'package:shangan_ios/features/focus/presentation/focus_duration_sheet.dart';
import 'package:shangan_ios/features/focus/presentation/time_up_alert.dart';

/// 服务端计时的专注页；本地时钟只用于平滑展示，恢复前台时会重新同步服务端。
/// 进行中返回须二次确认，确认后立即结束计时并离开。
final class FocusTimerPage extends ConsumerStatefulWidget {
  const FocusTimerPage({
    required this.title,
    this.plannedSeconds,
    this.planItemId,
    this.mediaItemId,
    super.key,
  });

  final String? planItemId;
  final String? mediaItemId;
  final String title;
  final int? plannedSeconds;

  @override
  ConsumerState<FocusTimerPage> createState() => _FocusTimerPageState();
}

final class _FocusTimerPageState extends ConsumerState<FocusTimerPage>
    with WidgetsBindingObserver {
  late final ScreenWakeLock _wakeLock;
  FocusSessionData? _session;
  DateTime? _receivedAtUtc;
  Object? _error;
  Timer? _ticker;
  bool _busy = false;
  bool _awaitingDuration = false;
  bool _leftForeground = false;
  bool _showingResumeWarning = false;
  bool _confirmingLeave = false;
  bool _timeUpAlertShown = false;

  @override
  void initState() {
    super.initState();
    _wakeLock = ref.read(screenWakeLockProvider);
    WidgetsBinding.instance.addObserver(this);
    _initialize();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _ticker?.cancel();
    unawaited(_wakeLock.disable());
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden) {
      if (_session?.status == 'RUNNING') _leftForeground = true;
      return;
    }
    if (state == AppLifecycleState.resumed) {
      _synchronizeActive();
      _maybeWarnStayOnPage();
    }
  }

  /// 计时仍在进行时拦截返回，确认后停表再离开。
  bool get _needsLeaveConfirm {
    final status = _session?.status;
    return status == 'RUNNING' || status == 'PAUSED';
  }

  bool get _keepScreenOn => _needsLeaveConfirm;

  Future<void> _initialize() async {
    try {
      final repository = ref.read(focusRepositoryProvider);
      final active = await repository.loadActive();
      if (active != null) {
        if (widget.planItemId != null &&
            active.planItemId != widget.planItemId) {
          throw StateError('已有其他专注任务正在进行，请先处理当前会话');
        }
        _accept(active);
        return;
      }
      final planned = widget.plannedSeconds;
      if (planned == null || planned <= 0) {
        if (mounted) setState(() => _awaitingDuration = true);
        return;
      }
      await _startWithDuration(planned);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  /// 按所选预设创建服务端会话，之后倒计时只展示服务端累计值。
  Future<void> _startWithDuration(int plannedSeconds) async {
    if (mounted) {
      setState(() {
        _awaitingDuration = false;
        _error = null;
      });
    }
    try {
      final session = await ref
          .read(focusRepositoryProvider)
          .start(
            planItemId: widget.planItemId,
            mediaItemId: widget.mediaItemId,
            focusType: 'POMODORO',
            plannedSeconds: plannedSeconds,
          );
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
      final remaining = (session.plannedSeconds - _displaySeconds(session))
          .clamp(0, session.plannedSeconds);
      if (remaining > 0) _timeUpAlertShown = false;
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
        if (!mounted) return;
        setState(() {});
        _maybeAlertTimeUp();
      });
    }
    _syncWakeLock();
  }

  /// 计划时长走完只弹一次到时闹钟，新开或重开会话会清标志。
  void _maybeAlertTimeUp() {
    final session = _session;
    if (session == null || session.status != 'RUNNING' || _timeUpAlertShown) {
      return;
    }
    final remaining = (session.plannedSeconds - _displaySeconds(session)).clamp(
      0,
      session.plannedSeconds,
    );
    if (remaining > 0) return;
    _timeUpAlertShown = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      unawaited(showTimeUpAlert(context, message: '专注时间已到，可以结束并结算。'));
    });
  }

  void _syncWakeLock() {
    if (_keepScreenOn) {
      unawaited(_wakeLock.enable());
    } else {
      unawaited(_wakeLock.disable());
    }
  }

  /// 从后台回来时提醒：计时未暂停，但离开会打断专注。
  void _maybeWarnStayOnPage() {
    if (!_leftForeground ||
        _showingResumeWarning ||
        _session?.status != 'RUNNING' ||
        !mounted) {
      return;
    }
    _leftForeground = false;
    _showingResumeWarning = true;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          key: const Key('focusStayOnPageDialog'),
          title: const Text('请留在专注页'),
          content: const Text(
            '切到其他 App 会打断专注，系统也可能回收进程。'
            '计时以服务端为准，不会因为离开而暂停或作废。',
          ),
          actions: [
            FilledButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('继续专注'),
            ),
          ],
        ),
      );
      if (mounted) _showingResumeWarning = false;
    });
  }

  /// 返回时二次确认：取消继续计时，确认则结束会话并离开。
  Future<void> _confirmStopAndLeave() async {
    if (_confirmingLeave || !mounted) return;
    _confirmingLeave = true;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        key: const Key('focusLeaveConfirmDialog'),
        title: const Text('结束计时并返回？'),
        content: const Text('确认后立即停止本次专注计时并离开此页。点错了请选继续专注。'),
        actions: [
          TextButton(
            key: const Key('stayFocus'),
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('继续专注'),
          ),
          FilledButton(
            key: const Key('confirmLeaveFocus'),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('结束并返回'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) {
      final session = _session;
      if (session != null && _needsLeaveConfirm) {
        setState(() => _busy = true);
        try {
          await ref.read(focusRepositoryProvider).finish(session.id);
        } catch (error) {
          if (mounted) {
            setState(() {
              _busy = false;
              _error = error;
            });
            _confirmingLeave = false;
            return;
          }
        }
      }
      if (mounted) Navigator.of(context).pop();
      return;
    }
    if (mounted) _confirmingLeave = false;
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
    return PopScope(
      key: const Key('focusPopScope'),
      canPop: !_needsLeaveConfirm,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(_confirmStopAndLeave());
      },
      child: Scaffold(
        appBar: AppBar(title: Text(widget.title)),
        body: SafeArea(
          child: _error != null
              ? _ErrorState(message: _error.toString(), retry: _initialize)
              : _awaitingDuration
              ? FocusDurationPicker(onSelected: _startWithDuration)
              : session == null
              ? const ShanganLoading('正在同步专注会话')
              : _buildSession(context, session),
        ),
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
          const SizedBox(height: 18),
          const ShanganNotice(
            title: '请勿切换到后台',
            message:
                '请尽量留在本页。离开会打断专注，系统也可能把 App 回收；'
                '重新打开后计时仍按服务端继续，不会作废。返回上一页会先确认，确认后立即停止计时。',
            tone: ShanganTagTone.warning,
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
