import 'package:shangan_ios/core/presence/app_activity.dart';
import 'dart:async';
import 'dart:math';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/device/attachment_picker.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/home/presentation/attachment_editor.dart';
import 'package:shangan_ios/features/home/presentation/complete_sheet.dart';

/// 可替换的显示时钟；服务端仍裁决最终状态与累计时长。
final focusNowProvider = Provider<DateTime Function()>((ref) => DateTime.now);

/// 专注计时运行页。
///
/// 倒计时结束判定完成；跳过记为未完成但保留已专注时长（见 ADR-0025）。
final class FocusRunPage extends ConsumerStatefulWidget {
  const FocusRunPage({required this.todoId, this.localDate, super.key});

  final String todoId;

  /// 从原 Todo 携带日期，历史项与跨午夜重载不能改查今日。
  final DateTime? localDate;

  @override
  ConsumerState<FocusRunPage> createState() => _FocusRunPageState();
}

class _FocusRunPageState extends ConsumerState<FocusRunPage>
    with WidgetsBindingObserver {
  late final ScreenWakeLock _wakeLock;
  bool _foreground = true;
  Timer? _ticker;
  TodoItem? _todo;
  bool _loading = true;
  String? _error;
  int _elapsedMs = 0;
  FocusState _state = FocusState.idle;
  bool get _running => _state == FocusState.running;
  DateTime? _segmentStart;
  int _savedMs = 0;
  String? _pendingAction;
  String? _pendingRequest;
  bool _finishing = false;
  bool _busy = false;

  /// 本地已知的完成凭证数量，初值来自日视图快照，「提前拍照」成功后加一。
  int _evidenceCount = 0;

  @override
  void initState() {
    super.initState();
    _wakeLock = ref.read(screenWakeLockProvider);
    WidgetsBinding.instance.addObserver(this);
    unawaited(_load());
  }

  @override
  void dispose() {
    _ticker?.cancel();
    ShanganFeedback.dismiss();
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_wakeLock.disable());
    super.dispose();
  }

  /// 前台运行专注时保持常亮；后台、暂停、结束及离页释放。
  void _syncWakeLock() => unawaited(
    _running && _foreground ? _wakeLock.enable() : _wakeLock.disable(),
  );

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _syncWakeLock();
    if (_foreground && !_busy && !_loading) unawaited(_load());
  }

  /// 重入页面和冲突恢复均读取服务端状态，不能按累计时长猜测暂停。
  Future<void> _load() async {
    try {
      final view = await ref
          .read(shanganRepositoryProvider)
          .loadDay(date: _todo?.localDate ?? widget.localDate);
      if (!mounted) return;
      final todo = view.todos.firstWhere(
        (item) => item.id == widget.todoId,
        orElse: () => throw StateError('专注待办不存在或日期已变更'),
      );
      setState(() {
        _todo = todo;
        _applySnapshot(todo);
        _evidenceCount = todo.attachmentCount;
        _loading = false;
      });
      _ticker?.cancel();
      if (_running) _startTicker();
      _syncWakeLock();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = '$error';
        _loading = false;
      });
    }
  }

  /// 每次以时间锚点计算显示值，后台停帧不会让倒计时丢秒。
  void _applySnapshot(TodoItem todo) {
    _state = todo.isDone ? FocusState.finished : todo.focusState;
    _savedMs = todo.focusAttemptMs;
    _segmentStart = _running
        ? todo.focusStartedAt ?? ref.read(focusNowProvider)()
        : null;
    _updateElapsed();
  }

  void _updateElapsed() {
    final delta = _segmentStart == null
        ? 0
        : ref
              .read(focusNowProvider)()
              .difference(_segmentStart!)
              .inMilliseconds;
    _elapsedMs = (_savedMs + max(0, delta))
        .clamp(0, (_todo?.plannedSeconds ?? 0) * 1000)
        .toInt();
  }

  void _startTicker() {
    _ticker?.cancel();
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(_updateElapsed);
      final planned = (_todo?.plannedSeconds ?? 0) * 1000;
      if (planned > 0 && _elapsedMs >= planned && !_busy && !_finishing) {
        unawaited(_finish());
      }
    });
  }

  String get _stateLabel => switch (_state) {
    FocusState.idle => '准备开始',
    FocusState.running => '正在专注',
    FocusState.paused => '已暂停',
    FocusState.stopped => '已停止',
    FocusState.finished => '已完成',
    FocusState.abandoned => '已跳过',
  };

  @override
  Widget build(BuildContext context) => AppActivityScope(
    activity: AppActivity(
      'FOCUS',
      _loading || _error != null
          ? 'UNKNOWN'
          : 'FOCUS_${_state.name.toUpperCase()}',
      widget.todoId,
    ),
    child: _buildPage(context),
  );

  /// 专注活动来自已接受的服务端状态；每秒计时重绘不会重复触发心跳。
  Widget _buildPage(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    final todo = _todo;
    if (todo == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Center(child: Text(_error ?? '待办不存在')),
      );
    }
    final plannedMs = (todo.plannedSeconds ?? 0) * 1000;
    final remainingMs = (plannedMs - _elapsedMs).clamp(0, plannedMs);
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 18),
          child: LayoutBuilder(
            builder: (context, constraints) => SingleChildScrollView(
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: constraints.maxHeight),
                child: IntrinsicHeight(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Row(
                        children: [
                          ShanganIconButton(
                            icon: Icons.close,
                            semanticLabel: '退出专注页',
                            onTap: () => Navigator.of(context).pop(true),
                          ),
                          const Spacer(),
                          if (_state != FocusState.abandoned &&
                              _state != FocusState.finished)
                            TextButton.icon(
                              onPressed: _busy ? null : _confirmAbandon,
                              icon: const Icon(
                                Icons.skip_next_rounded,
                                size: 18,
                              ),
                              label: const Text('跳过'),
                              style: TextButton.styleFrom(
                                foregroundColor: ShanganColors.mutedInk,
                              ),
                            ),
                        ],
                      ),
                      const Spacer(),
                      const SizedBox(height: 24),
                      Text(
                        todo.title,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 18),
                      // 环形进度围绕剩余时间，进度颜色与文字共同表达当前状态。
                      Center(
                        child: SizedBox.square(
                          dimension: (constraints.maxWidth - 32).clamp(
                            220.0,
                            288.0,
                          ),
                          child: Stack(
                            alignment: Alignment.center,
                            children: [
                              Positioned.fill(
                                child: Padding(
                                  padding: const EdgeInsets.all(8),
                                  child: Semantics(
                                    label: '专注倒计时',
                                    value: '剩余 ${formatPosition(remainingMs)}',
                                    child: CircularProgressIndicator(
                                      // 圆环表示本轮剩余时间，不表示跨轮累计的完成率。
                                      // 暂停保留当前值，新一轮从完整倒计时开始。
                                      value: plannedMs == 0
                                          ? 0
                                          : (remainingMs / plannedMs).clamp(
                                              0.0,
                                              1.0,
                                            ),
                                      strokeWidth: 10,
                                      strokeCap: StrokeCap.round,
                                      backgroundColor:
                                          ShanganColors.progressTrack,
                                      color: ShanganColors.ochre,
                                    ),
                                  ),
                                ),
                              ),
                              Padding(
                                padding: const EdgeInsets.all(30),
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Text(
                                      _stateLabel,
                                      style: const TextStyle(
                                        color: ShanganColors.ochre,
                                        fontSize: 14,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                    const SizedBox(height: 10),
                                    FittedBox(
                                      fit: BoxFit.scaleDown,
                                      child: Text(
                                        formatPosition(remainingMs),
                                        style: const TextStyle(
                                          fontSize: 58,
                                          height: 1.1,
                                          fontWeight: FontWeight.w800,
                                          letterSpacing: -2,
                                          fontFeatures: [
                                            FontFeature.tabularFigures(),
                                          ],
                                        ),
                                      ),
                                    ),
                                    const SizedBox(height: 10),
                                    const Text(
                                      '剩余时间',
                                      style: TextStyle(
                                        color: ShanganColors.mutedInk,
                                        fontSize: 12,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 20),
                      ShanganCard(
                        child: Column(
                          children: [
                            Text(
                              '目标 ${formatPosition(plannedMs)} · 本次已专注 ${formatPosition(_elapsedMs)}',
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              _state == FocusState.finished ? '已完成' : '未完成',
                              style: const TextStyle(
                                color: ShanganColors.mutedInk,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 24),
                      const Spacer(),
                      if (_state == FocusState.idle ||
                          _state == FocusState.stopped)
                        FilledButton.icon(
                          onPressed: _busy ? null : _start,
                          icon: const Icon(Icons.play_arrow_rounded),
                          label: Text(
                            _state == FocusState.stopped ? '再次开始' : '开始专注',
                          ),
                        ),
                      if (_state.isActive)
                        Row(
                          children: [
                            Expanded(
                              child: FilledButton.icon(
                                onPressed: _busy ? null : _togglePause,
                                icon: Icon(
                                  _running
                                      ? Icons.pause_rounded
                                      : Icons.play_arrow_rounded,
                                ),
                                label: Text(_running ? '暂停' : '继续'),
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: OutlinedButton.icon(
                                onPressed: _busy ? null : _confirmStop,
                                icon: const Icon(Icons.stop_rounded),
                                label: const Text('停止'),
                              ),
                            ),
                          ],
                        ),
                      if (_state == FocusState.abandoned ||
                          _state == FocusState.finished)
                        FilledButton(
                          onPressed: () => Navigator.of(context).maybePop(true),
                          child: const Text('返回待办'),
                        ),
                      if (_state == FocusState.paused && remainingMs == 0)
                        TextButton(
                          onPressed: _busy ? null : _finish,
                          child: const Text('提交完成'),
                        ),
                      const SizedBox(height: 16),
                      ShanganTip(
                        text: _state == FocusState.abandoned
                            ? '本次已跳过，专注时长已保留，待办仍记为未完成。'
                            : '暂停和停止均保留已专注时长；跳过后本次不再计时。',
                      ),
                      if (todo.requireEvidence) ...[
                        const SizedBox(height: 12),
                        ShanganCard(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 14,
                            vertical: 12,
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const Row(
                                children: [
                                  Text(
                                    '完成后需要',
                                    style: TextStyle(
                                      fontSize: 12.5,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                  Spacer(),
                                  ShanganBadge(
                                    label: '拍照 1 张',
                                    tone: ShanganBadgeTone.ochre,
                                  ),
                                ],
                              ),
                              const Padding(
                                padding: EdgeInsets.symmetric(vertical: 12),
                                child: Divider(
                                  height: 1,
                                  color: ShanganColors.hair,
                                ),
                              ),
                              Row(
                                children: [
                                  Expanded(
                                    child: OutlinedButton.icon(
                                      style: _smallButtonStyle,
                                      onPressed: _busy
                                          ? null
                                          : () => unawaited(
                                              _captureEvidence(todo),
                                            ),
                                      icon: const Icon(
                                        Icons.photo_camera_outlined,
                                        size: 18,
                                      ),
                                      label: const Text('提前拍照'),
                                    ),
                                  ),
                                  const SizedBox(width: 9),
                                  Expanded(
                                    child: OutlinedButton.icon(
                                      style: _smallButtonStyle,
                                      onPressed: _busy
                                          ? null
                                          : () =>
                                                unawaited(_openBackfill(todo)),
                                      icon: const Icon(
                                        Icons.sticky_note_2_outlined,
                                        size: 18,
                                      ),
                                      label: const Text('备注'),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ],
                      const SizedBox(height: 16),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  static final _smallButtonStyle = OutlinedButton.styleFrom(
    minimumSize: const Size.fromHeight(44),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700),
  );

  /// 原型 3-2「提前拍照」：不等专注结束就先把凭证拍好上传。
  ///
  /// 这里不重新拉日视图，避免打断正在跑的倒计时；只把本地凭证计数加一，
  /// 完成时的门控用它判断是否还需要先弹回填面板。
  Future<void> _captureEvidence(TodoItem todo) async {
    setState(() => _busy = true);
    try {
      final picked = await ref
          .read(attachmentPickerProvider)
          .pick(AttachmentSource.camera);
      if (picked == null) {
        if (mounted) setState(() => _busy = false);
        return;
      }
      await ref
          .read(shanganRepositoryProvider)
          .uploadAttachment(
            todo.id,
            filename: picked.filename,
            contentType: picked.contentType,
            bytes: picked.bytes,
          );
      if (!mounted) return;
      setState(() {
        _busy = false;
        _evidenceCount += 1;
      });
      ShanganFeedback.show(context, '凭证已上传');
    } catch (error) {
      if (!mounted) return;
      setState(() => _busy = false);
      ShanganFeedback.show(context, attachmentErrorMessage(error), error: true);
    }
  }

  /// 原型 3-2「备注」：提前打开完成回填面板填备注与附件。
  Future<void> _openBackfill(TodoItem todo) async {
    await CompleteSheet.show(context, todo: todo);
    if (mounted) await _load();
  }

  /// 只有成功返回后应用新状态；网络失败保留同一请求标识供重试。
  Future<bool> _action(String action) async {
    if (_busy) return false;
    setState(() => _busy = true);
    if (_pendingAction != action) {
      _pendingAction = action;
      _pendingRequest = List.generate(
        16,
        (_) => Random.secure().nextInt(256).toRadixString(16).padLeft(2, '0'),
      ).join();
    }
    try {
      final result = await ref
          .read(shanganRepositoryProvider)
          .focusAction(widget.todoId, action, requestId: _pendingRequest);
      if (!mounted) return false;
      _ticker?.cancel();
      setState(() {
        _applySnapshot(result);
        _busy = false;
        _pendingAction = null;
        _pendingRequest = null;
      });
      if (_running) _startTicker();
      _syncWakeLock();
      return true;
    } catch (error) {
      if (!mounted) return false;
      setState(() => _busy = false);
      if (error is ApiException &&
          error.errorCode == 'FOCUS_ILLEGAL_TRANSITION') {
        await _load();
        if (mounted) {
          ShanganFeedback.show(context, '专注状态已更新，已刷新当前页面', error: true);
        }
      } else {
        _showError(error);
      }
      return false;
    }
  }

  Future<void> _start() async {
    await _action('start');
  }

  Future<void> _togglePause() async {
    await _action(_running ? 'pause' : 'resume');
  }

  /// 到点先暂停落账，回填取消也不继续累计等待凭证的时间。
  Future<void> _finish() async {
    if (_finishing || _busy) return;
    _finishing = true;
    try {
      if (_running && !await _action('pause')) return;
      if (!mounted) return;
      final todo = _todo;
      if (todo == null) return;
      if (todo.requireEvidence && _evidenceCount == 0) {
        final completed = await CompleteSheet.show(context, todo: todo);
        if (!mounted) return;
        await _load();
        if (!completed) return;
        // 回填面板可能已经完成 Todo，按服务端事实结束页面。
        if (_state == FocusState.finished) {
          if (mounted) Navigator.of(context).maybePop(true);
          return;
        }
      }
      if (await _action('finish') && mounted) {
        Navigator.of(context).maybePop(true);
      }
    } finally {
      _finishing = false;
    }
  }

  /// 停止和跳过的结果不同，面板明确说明并默认允许安全返回。
  Future<bool> _confirm({required bool skip}) async {
    return await showModalBottomSheet<bool>(
          context: context,
          showDragHandle: true,
          isScrollControlled: true,
          builder: (context) => SafeArea(
            child: SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      skip ? '跳过这次专注？' : '停止这次计时？',
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      skip
                          ? '会记为未完成，但已专注的时长仍然保留并进入统计。'
                          : '已专注时长会保存，再次开始会从零计时，倒计时恢复为设定时长。',
                    ),
                    const SizedBox(height: 24),
                    FilledButton(
                      onPressed: () => Navigator.of(context).pop(false),
                      child: const Text('返回专注'),
                    ),
                    const SizedBox(height: 8),
                    OutlinedButton(
                      onPressed: () => Navigator.of(context).pop(true),
                      child: Text(skip ? '确认跳过' : '确认停止'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ) ??
        false;
  }

  Future<void> _confirmStop() async {
    if (!await _confirm(skip: false) || !mounted) return;
    if (await _action('stop') && mounted) {
      ShanganFeedback.show(context, '已停止，专注时长已保存');
    }
  }

  Future<void> _confirmAbandon() async {
    if (!await _confirm(skip: true) || !mounted) return;
    if (await _action('abandon') && mounted) {
      ShanganFeedback.show(context, '已跳过，专注时长已保留');
    }
  }

  void _showError(Object error) {
    if (!mounted) return;
    ShanganFeedback.show(
      context,
      shanganErrorMessage(error, '操作未成功，请检查网络后重试'),
      error: true,
    );
  }
}
