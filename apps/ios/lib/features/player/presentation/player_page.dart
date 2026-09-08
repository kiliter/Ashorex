import 'package:shangan_ios/core/presence/app_activity.dart';
export 'package:shangan_ios/core/player/progress_queue.dart' show ProgressQueue;
import 'package:shangan_ios/core/player/progress_queue.dart';
import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'dart:async';
import 'package:shangan_ios/core/player/playback_adapter.dart';
import 'package:shangan_ios/core/device/player_device_status.dart';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/home/presentation/complete_sheet.dart';

/// 课程播放页。
///
/// V2 允许自由拖动与快进；服务端只接收最远位置与前台累计时长，
/// 是否达标由服务端裁决（见 ADR-0025）。
final class PlayerPage extends ConsumerStatefulWidget {
  const PlayerPage({required this.todoId, this.localDate, super.key});

  final String todoId;

  /// 从原 Todo 携带日期，历史项与跨午夜重载不能改查今日。
  final DateTime? localDate;

  @override
  ConsumerState<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends ConsumerState<PlayerPage>
    with WidgetsBindingObserver {
  static const _reportInterval = Duration(seconds: 15);
  static const _speeds = [0.75, 1.0, 1.25, 1.5, 2.0];

  PlaybackAdapter? _player;
  bool _commandBusy = false;
  bool _foreground = true;
  bool _seeking = false;
  late final int Function() _time;
  bool _counting = false;
  bool _pausing = false;
  int _watchSample = 0;
  late final PlaybackAdapter Function() _playerFactory;

  late final ProgressQueue _queue;

  /// 唤醒锁在 initState 缓存：`dispose()` 里不能再通过 `ref` 取 Provider，
  /// 否则 widget 已卸载时会抛「Using ref when a widget has been unmounted」。
  late final ScreenWakeLock _wakeLock;
  Timer? _reporter;
  Timer? _controlsTimer;
  Timer? _gestureTimer;
  String _bufferingLabel = '正在缓冲';
  bool _controlsVisible = true;
  bool _endReported = false;

  TodoItem? _todo;
  bool _loading = true;
  // 每轮加载独立编号，超时或退出后的迟到结果不能覆盖当前页面。
  int _loadGeneration = 0;
  String? _error;
  bool _playing = false;
  bool _fullscreen = false;
  double _speed = 1.0;
  bool _showSpeeds = false;
  double _doubleTapX = 0;
  int _gestureSerial = 0;
  IconData? _gestureIcon;
  String _gestureLabel = '';
  Alignment _gestureAlignment = Alignment.center;
  Future<void> _speedCommands = Future<void>.value();
  int _speedRevision = 0;
  double _confirmedSpeed = 1.0;
  int _positionMs = 0;
  int _sessionWatchedMs = 0;
  int _uncommittedMs = 0;
  int _durationMs = 0;
  bool _completed = false;
  int _progressPermille = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _queue = ProgressQueue(
      repository: ref.read(shanganRepositoryProvider),
      todoId: widget.todoId,
      outbox: ref.read(progressOutboxProvider),
    );
    _wakeLock = ref.read(screenWakeLockProvider);
    _playerFactory = ref.read(playbackAdapterFactoryProvider);
    _time = ref.read(playbackTimeProvider);
    _watchSample = _time();
    unawaited(_load());
  }

  @override
  void dispose() {
    _loadGeneration++;
    WidgetsBinding.instance.removeObserver(this);
    _reporter?.cancel();
    _controlsTimer?.cancel();
    _gestureTimer?.cancel();
    _collectWatch();
    _counting = false;
    // 退出按暂停补报，使用服务端与数据库共同支持的事件类型。
    unawaited(_report(force: true, eventType: 'PAUSE'));
    _player?.removeListener(_onPlayerChanged);
    _player?.dispose();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    SystemChrome.setPreferredOrientations(const [DeviceOrientation.portraitUp]);
    unawaited(_wakeLock.disable());
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    // 切后台一律暂停并补报一次，后台不累计时长。
    if (state != AppLifecycleState.resumed && _playing) {
      unawaited(_pause());
    }
  }

  /// 整段加载设定上限，覆盖接口、内核初始化和恢复位置，避免无限等待。
  Future<void> _load() async {
    final generation = ++_loadGeneration;
    try {
      await _loadPlayback(generation).timeout(const Duration(seconds: 30));
    } catch (_) {
      if (!mounted || generation != _loadGeneration) return;
      _loadGeneration++;
      _player?.removeListener(_onPlayerChanged);
      _player?.dispose();
      _player = null;
      setState(() {
        _playing = false;
        _loading = false;
        _error = '视频加载失败或超时，请重新加载';
      });
    }
  }

  /// 每个异步阶段都核对本轮身份；先挂载画面，再恢复位置。
  Future<void> _loadPlayback(int generation) async {
    bool active() => mounted && generation == _loadGeneration;
    final view = await _queue.repository.loadDay(
      date: _todo?.localDate ?? widget.localDate,
    );
    if (!active()) return;
    final todo = view.todos.firstWhere(
      (item) => item.id == widget.todoId,
      orElse: () => throw StateError('待办不存在或日期已变更'),
    );
    setState(() {
      _todo = todo;
      _durationMs = todo.resourceDurationMs ?? 0;
    });
    if (_player == null) {
      final source = await _queue.repository.playbackSource(todo.resourceId!);
      if (!active()) return;
      final player = _playerFactory();
      _player = player;
      await player.initialize(source.uri, source.headers);
      if (!active()) return;
      // 原生视图先进入布局，避免把首帧展示依赖于远端 seek 完成。
      setState(() {});
      await WidgetsBinding.instance.endOfFrame;
      if (!active()) return;
      if (todo.progressPositionMs > 0) {
        await player
            .seek(todo.progressPositionMs)
            .timeout(const Duration(seconds: 8));
      }
      if (!active()) return;
      player.addListener(_onPlayerChanged);
    }
    if (!active()) return;
    setState(() {
      _positionMs = _player!.positionMs;
      _durationMs = _player!.durationMs > 0
          ? _player!.durationMs
          : todo.resourceDurationMs ?? 0;
      _progressPermille = todo.progressPermille;
      _completed = todo.isDone;
      _loading = false;
      _error = null;
    });
  }

  @override
  Widget build(BuildContext context) => AppActivityScope(
    activity: AppActivity('PLAYER', _activityState, widget.todoId),
    child: _buildPage(context),
  );

  /// 顺序按真实内核状态判定，缓冲和错误不能标为播放中。
  String get _activityState {
    if (_error != null) return 'VIDEO_ERROR';
    if (_loading) return 'VIDEO_LOADING';
    if (_player?.ended ?? false) return 'VIDEO_ENDED';
    if (_player?.buffering ?? false) return 'VIDEO_BUFFERING';
    return _playing && _foreground ? 'VIDEO_PLAYING' : 'VIDEO_PAUSED';
  }

  Widget _buildPage(BuildContext context) {
    if (_loading && _todo == null) {
      // 首次接口请求期间也保留返回入口。
      return Scaffold(
        appBar: AppBar(),
        body: const Center(child: CircularProgressIndicator()),
      );
    }
    final todo = _todo;
    if (todo == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_error ?? '待办不存在'),
              TextButton(onPressed: _retry, child: const Text('重试')),
            ],
          ),
        ),
      );
    }
    if (_fullscreen) {
      return PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, result) {
          if (!didPop) _exitFullscreen();
        },
        child: Scaffold(
          // 画面铺满屏幕；安全区只约束操作层，不再形成外围色框。
          backgroundColor: Colors.black,
          body: _videoSurface(todo, fullscreen: true),
        ),
      );
    }
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _videoSurface(todo, fullscreen: false),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(18, 14, 18, 24),
                children: [
                  Text(
                    todo.title,
                    style: const TextStyle(
                      fontSize: 17,
                      height: 1.32,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    todo.resourceTitle ?? '课程课时',
                    style: const TextStyle(
                      fontSize: 12.5,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                  const SizedBox(height: 12),
                  ShanganCard(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 12,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            const Text(
                              '观看进度',
                              style: TextStyle(
                                fontSize: 12.5,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            const Spacer(),
                            Text(
                              '${_progressPermille ~/ 10}%'
                              '${todo.targetProgressPermille == null ? '' : ' / 目标 ${todo.targetProgressPermille! ~/ 10}%'}',
                              style: const TextStyle(
                                fontSize: 12.5,
                                fontWeight: FontWeight.w800,
                                fontFeatures: [FontFeature.tabularFigures()],
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        TargetProgressBar(
                          value: _progressPermille / 1000,
                          targetValue: todo.targetProgressPermille == null
                              ? null
                              : todo.targetProgressPermille! / 1000,
                          height: 7,
                        ),
                        const SizedBox(height: 10),
                        Wrap(
                          spacing: 12,
                          runSpacing: 4,
                          children: [
                            Text(
                              '最远位置 ${formatPosition(_positionMs)}'
                              '${_targetMs(todo) == null ? '' : ' · 目标 ${formatPosition(_targetMs(todo)!)}'}',
                              style: const TextStyle(
                                fontSize: 11.5,
                                color: ShanganColors.mutedInk,
                              ),
                            ),
                            Text(
                              _completed
                                  ? '已达标'
                                  : _remainingMs(todo) == null
                                  ? '手动标记完成'
                                  : '还需 ${formatPosition(_remainingMs(todo)!)}',
                              style: const TextStyle(
                                fontSize: 11.5,
                                color: ShanganColors.mutedInk,
                              ),
                            ),
                          ],
                        ),
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 12),
                          child: Divider(height: 1, color: ShanganColors.hair),
                        ),
                        // 目标与操作同排，完整说明独占下一行，避免被两侧控件挤出孤字。
                        Row(
                          children: [
                            if (todo.targetProgressPermille != null)
                              ShanganBadge(
                                label:
                                    '目标 ${todo.targetProgressPermille! ~/ 10}%',
                                tone: ShanganBadgeTone.blue,
                              ),
                            const Spacer(),
                            ShanganFilterChip(
                              label: '调整目标',
                              selected: false,
                              onTap: () => _adjustTarget(todo),
                            ),
                          ],
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          '达到目标即自动完成，可继续看完',
                          style: TextStyle(
                            fontSize: 11.5,
                            color: ShanganColors.mutedInk,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 9,
                    runSpacing: 9,
                    children: [
                      SizedBox(
                        child: OutlinedButton.icon(
                          style: _smallButtonStyle,
                          onPressed: () => _openCompleteSheet(todo),
                          icon: const Icon(Icons.attach_file, size: 18),
                          label: Text(
                            todo.attachmentCount > 0
                                ? '附件 ${todo.attachmentCount}'
                                : '附件',
                          ),
                        ),
                      ),
                      SizedBox(
                        child: OutlinedButton.icon(
                          style: _smallButtonStyle,
                          onPressed: () => _openCompleteSheet(todo),
                          icon: const Icon(
                            Icons.sticky_note_2_outlined,
                            size: 18,
                          ),
                          label: const Text('备注'),
                        ),
                      ),
                      SizedBox(
                        child: FilledButton.icon(
                          style: FilledButton.styleFrom(
                            minimumSize: const Size(0, 44),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                            textStyle: const TextStyle(
                              fontSize: 13.5,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          onPressed: _completed
                              ? null
                              : () => _markComplete(todo),
                          icon: const Icon(Icons.check, size: 18),
                          label: Text(_completed ? '已完成' : '标记完成'),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  Text(
                    '本次观看 ${formatDurationCompact(_sessionWatchedMs)}'
                    ' · 累计 ${formatDurationCompact(todo.watchedMs + _sessionWatchedMs)}'
                    '${_queue.depth > 0 ? ' · ${_queue.depth} 条待同步' : ''}',
                    style: const TextStyle(
                      fontSize: 11.5,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 共用动作只负责播放控制，横竖屏分别安排位置。
  Widget _playButton() => _VideoButton(
    icon: _playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
    semanticLabel: _playing ? '暂停' : '播放',
    onTap: _togglePlay,
  );

  /// 倍速入口与面板始终留在视频内部。
  Widget _speedButton() => Tooltip(
    message: '选择倍速',
    child: _VideoTool(
      label: _speed == 1 ? '倍速' : '${_speed}x',
      onTap: () {
        _controlsTimer?.cancel();
        setState(() => _showSpeeds = true);
      },
    ),
  );

  /// 全屏使用纯图标，语义与提示仍保留完整名称。
  Widget _fullscreenButton(bool fullscreen) => _VideoButton(
    icon: fullscreen ? Icons.fullscreen_exit : Icons.fullscreen,
    semanticLabel: fullscreen ? '退出全屏' : '全屏',
    onTap: () {
      _revealControls();
      if (fullscreen) {
        _exitFullscreen();
      } else {
        _enterFullscreen();
      }
    },
  );

  /// 时间保持单行，继续遵循系统字号设置。
  Widget _playbackTime() => Text(
    '${formatPosition(_positionMs)}/${formatPosition(_durationMs)}',
    key: const Key('playerTime'),
    maxLines: 1,
    style: _timeStyle.copyWith(fontSize: 12, fontWeight: FontWeight.w400),
  );

  /// 拖动期间暂停自动隐藏，松手后沿用现有跳转和进度上报逻辑。
  Widget _playbackTimeline(TodoItem todo) => _Timeline(
    positionMs: _positionMs,
    durationMs: _durationMs,
    onChanged: (value) {
      _controlsTimer?.cancel();
      _collectWatch();
      _counting = false;
      _seeking = true;
      setState(() => _positionMs = value);
    },
    onChangeEnd: () {
      _revealControls();
      unawaited(_seekTo(_positionMs));
    },
  );

  /// 选中立即收起面板；原生命令按顺序异步执行，失败恢复已确认倍速。
  void _selectSpeed(double speed) {
    final player = _player;
    if (_loading || player == null) return;
    final revision = ++_speedRevision;
    setState(() {
      _speed = speed;
      _showSpeeds = false;
    });
    _revealControls();
    _speedCommands = _speedCommands.then((_) async {
      if (!mounted || !identical(_player, player)) return;
      try {
        await player.speed(speed).timeout(const Duration(seconds: 8));
        if (!mounted || !identical(_player, player)) return;
        _confirmedSpeed = speed;
      } catch (_) {
        if (!mounted ||
            !identical(_player, player) ||
            revision != _speedRevision) {
          return;
        }
        setState(() => _speed = _confirmedSpeed);
        ShanganFeedback.show(context, '倍速设置失败，请重试', error: true);
      }
    });
  }

  /// 中央图标表达下一步动作，单击立即切换，独立于画面的双击识别。
  Widget _centerPlaybackControl({required Widget child}) => Semantics(
    button: true,
    label: _playing ? '暂停播放' : '继续播放',
    child: GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () {
        if (_loading || _commandBusy || _player == null) return;
        _gestureTimer?.cancel();
        setState(() => _gestureIcon = null);
        _revealControls();
        _togglePlay();
      },
      child: child,
    ),
  );

  /// 双击确认后立即呈现对应动作；动画不等待原生跳转或播放命令。
  void _handlePictureDoubleTap(double width) {
    if (_loading ||
        _error != null ||
        _showSpeeds ||
        _commandBusy ||
        _player == null) {
      return;
    }
    final zone = (_doubleTapX / (width / 3)).floor().clamp(0, 2);
    final wasPlaying = _playing;
    setState(() {
      _gestureSerial++;
      _gestureAlignment = Alignment((zone - 1) * 2 / 3, 0);
      _gestureIcon = zone == 0
          ? Icons.fast_rewind_rounded
          : zone == 2
          ? Icons.fast_forward_rounded
          : wasPlaying
          ? Icons.pause_rounded
          : Icons.play_arrow_rounded;
      _gestureLabel = zone == 0
          ? '快退 10 秒'
          : zone == 2
          ? '快进 10 秒'
          : wasPlaying
          ? '暂停'
          : '播放';
    });
    _gestureTimer?.cancel();
    _gestureTimer = Timer(const Duration(milliseconds: 650), () {
      if (mounted) setState(() => _gestureIcon = null);
    });
    _revealControls();
    if (zone == 1) {
      _togglePlay();
    } else {
      _seekBy(zone == 0 ? -10000 : 10000);
    }
  }

  /// 无色块与边框的倍速选项，选中项用文字和下划线共同标识。
  Widget _speedOption(double speed) => Semantics(
    selected: speed == _speed,
    button: true,
    child: GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => _selectSpeed(speed),
      child: Container(
        key: ValueKey('playerSpeed-$speed'),
        constraints: const BoxConstraints(minHeight: 44),
        alignment: Alignment.center,
        child: Text(
          '${speed}x',
          maxLines: 1,
          style: TextStyle(
            color: speed == _speed ? const Color(0xFFFF6699) : Colors.white,
            fontSize: 14,
            fontWeight: speed == _speed ? FontWeight.w600 : FontWeight.w400,
            decoration: speed == _speed
                ? TextDecoration.underline
                : TextDecoration.none,
            decorationColor: const Color(0xFFFF6699),
          ),
        ),
      ),
    ),
  );

  /// 手机播放器：画面铺满区域，所有控制浮在画面内，隐藏时不占布局空间。
  Widget _videoSurface(TodoItem todo, {required bool fullscreen}) {
    final visible = _controlsVisible || !_playing;
    final insets = fullscreen
        ? MediaQuery.viewPaddingOf(context)
        : EdgeInsets.zero;
    final picture = LayoutBuilder(
      builder: (context, constraints) => ColoredBox(
        key: const Key('playerPicture'),
        color: Colors.black,
        child: Stack(
          fit: StackFit.expand,
          children: [
            RepaintBoundary(
              child: _player?.buildVideo() ?? const SizedBox.shrink(),
            ),
            // 画面手势与工具栏是同级层；点击倍速不再参与双击等待。
            Positioned.fill(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                // 记录双击位置，待手势确认后再执行，避免第二次按下即误触播放。
                onDoubleTapDown: (details) =>
                    _doubleTapX = details.localPosition.dx,
                onDoubleTap: () =>
                    _handlePictureDoubleTap(constraints.maxWidth),
                onTap: () {
                  if (_showSpeeds) {
                    setState(() => _showSpeeds = false);
                    _revealControls();
                  } else if (_playing && _controlsVisible) {
                    _controlsTimer?.cancel();
                    setState(() => _controlsVisible = false);
                  } else {
                    _revealControls();
                  }
                },
                child: const SizedBox.expand(),
              ),
            ),
            // 遮罩只随操作层出现，白色课件上也能辨认；白色图标不再添加圆底或胶囊边框。
            if (visible && !_showSpeeds) ...[
              const IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [
                        Color(0xB3000000),
                        Color(0x00000000),
                        Color(0xCC000000),
                      ],
                      stops: [0, 0.45, 1],
                    ),
                  ),
                ),
              ),
              Positioned(
                top: insets.top,
                left: insets.left,
                right: insets.right,
                child: SizedBox(
                  height: 44,
                  child: Row(
                    children: [
                      IconButton(
                        tooltip: fullscreen ? '退出全屏' : '关闭播放',
                        onPressed: () {
                          if (fullscreen) {
                            _exitFullscreen();
                          } else {
                            Navigator.of(context).pop(true);
                          }
                        },
                        icon: const Icon(
                          Icons.arrow_back_ios_new_rounded,
                          color: Colors.white,
                          size: 20,
                        ),
                      ),
                      if (fullscreen) ...[
                        Expanded(
                          child: Text(
                            todo.title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 15,
                            ),
                          ),
                        ),
                        const PlayerDeviceStatus(),
                      ] else ...[
                        const Spacer(),
                        _speedButton(),
                      ],
                      const SizedBox(width: 12),
                    ],
                  ),
                ),
              ),
              // 竖屏单行控制；横屏依次排列时间、通栏进度与底部工具。
              Positioned(
                left: fullscreen ? insets.left + 12 : 4,
                right: fullscreen ? insets.right + 12 : 4,
                bottom: fullscreen ? insets.bottom + 8 : 2,
                child: fullscreen
                    ? Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _playbackTime(),
                          _playbackTimeline(todo),
                          Row(
                            children: [
                              if (_error == null && !_loading) ...[
                                _playButton(),
                              ],
                              const Spacer(),
                              _speedButton(),
                              const SizedBox(width: 16),
                              _fullscreenButton(fullscreen),
                            ],
                          ),
                        ],
                      )
                    : Row(
                        children: [
                          if (_error == null && !_loading) _playButton(),
                          Expanded(child: _playbackTimeline(todo)),
                          _playbackTime(),
                          _fullscreenButton(fullscreen),
                        ],
                      ),
              ),
            ],
            if (!visible && !_showSpeeds)
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: IgnorePointer(
                  child: LinearProgressIndicator(
                    minHeight: 2,
                    value: _durationMs > 0
                        ? (_positionMs / _durationMs).clamp(0.0, 1.0)
                        : 0,
                    color: const Color(0xFF8AB8F8),
                    backgroundColor: const Color(0xFF40444B),
                  ),
                ),
              ),
            if (_error != null && !_showSpeeds)
              Center(
                child: Material(
                  color: const Color(0xFF17191E),
                  borderRadius: BorderRadius.circular(12),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Text('播放遇到问题', style: _timeStyle),
                        TextButton(
                          onPressed: _retry,
                          child: const Text(
                            '重新加载',
                            style: TextStyle(color: Colors.white),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            // 倍速面板直接出现与关闭，不执行位移或淡入淡出动画。
            if (_showSpeeds)
              Align(
                alignment: fullscreen
                    ? Alignment.centerRight
                    : Alignment.bottomCenter,
                child: SizedBox(
                  width: fullscreen ? 240 : double.infinity,
                  height: fullscreen ? double.infinity : null,
                  child: Material(
                    key: const Key('playerSpeedPanel'),
                    color: const Color(0xF20B0B0B),
                    child: SingleChildScrollView(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Row(
                              children: [
                                const Expanded(
                                  child: Text('播放速度', style: _timeStyle),
                                ),
                                IconButton(
                                  tooltip: '关闭倍速选择',
                                  onPressed: () {
                                    setState(() => _showSpeeds = false);
                                    _revealControls();
                                  },
                                  icon: const Icon(
                                    Icons.close,
                                    color: Colors.white,
                                  ),
                                ),
                              ],
                            ),
                            // 竖屏五档均分一行，横屏使用无边框文字列表。
                            if (fullscreen)
                              Column(
                                children: [
                                  for (final speed in _speeds)
                                    _speedOption(speed),
                                ],
                              )
                            else
                              Row(
                                children: [
                                  for (final speed in _speeds)
                                    Expanded(child: _speedOption(speed)),
                                ],
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            // 持续加载与短暂双击反馈分离，原生跳转未完成时始终有状态提示。
            if (!_showSpeeds && _error == null)
              if (_loading || _seeking || _player?.buffering == true)
                IgnorePointer(
                  child: Center(
                    child: Container(
                      key: const Key('playerWaiting'),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: const Color(0xB3000000),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const SizedBox.square(
                            dimension: 28,
                            child: CircularProgressIndicator(
                              color: Colors.white,
                              strokeWidth: 2,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            _loading ? '正在加载' : _bufferingLabel,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                )
              else if (!_playing && _gestureIcon == null)
                Center(
                  child: _centerPlaybackControl(
                    child: const DecoratedBox(
                      key: Key('playerPaused'),
                      decoration: BoxDecoration(
                        color: Color(0x99000000),
                        shape: BoxShape.circle,
                      ),
                      child: Padding(
                        padding: EdgeInsets.all(12),
                        child: Icon(
                          Icons.play_arrow_rounded,
                          color: Colors.white,
                          size: 36,
                        ),
                      ),
                    ),
                  ),
                ),
            if (_gestureIcon != null && !_showSpeeds)
              Align(
                alignment: _gestureAlignment,
                child: _gestureAlignment == Alignment.center
                    ? _centerPlaybackControl(
                        child: _PlayerGestureFeedback(
                          key: ValueKey(_gestureSerial),
                          icon: _playing
                              ? Icons.pause_rounded
                              : Icons.play_arrow_rounded,
                          label: _playing ? '暂停' : '播放',
                        ),
                      )
                    : IgnorePointer(
                        child: _PlayerGestureFeedback(
                          key: ValueKey(_gestureSerial),
                          icon: _gestureIcon!,
                          label: _gestureLabel,
                        ),
                      ),
              ),
          ],
        ),
      ),
    );
    return fullscreen
        ? picture
        : AspectRatio(aspectRatio: 16 / 9, child: picture);
  }

  /// 操作后保留三秒浮层；暂停时始终可操作，选择倍速和拖动时暂停隐藏。
  void _revealControls() {
    _controlsTimer?.cancel();
    if (!mounted) return;
    setState(() => _controlsVisible = true);
    if (_playing) {
      _controlsTimer = Timer(const Duration(seconds: 3), () {
        if (mounted) setState(() => _controlsVisible = false);
      });
    }
  }

  static const _timeStyle = TextStyle(
    color: Colors.white,
    fontSize: 11,
    fontFeatures: [FontFeature.tabularFigures()],
  );

  /// 进入全屏并锁横屏（原型 3-1b 是横屏全屏播放）。
  void _enterFullscreen() {
    setState(() => _fullscreen = true);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    SystemChrome.setPreferredOrientations(const [
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
  }

  /// 离开全屏恢复系统栏与竖屏方向。
  void _exitFullscreen() {
    setState(() => _fullscreen = false);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    SystemChrome.setPreferredOrientations(const [DeviceOrientation.portraitUp]);
  }

  /// 重试重新获取当前凭据并创建播放器，不复用已失效的原生连接。
  Future<void> _retry() async {
    if (_loading) return;
    // 失败内核的暂停可能不返回，重试直接释放；进度独立进入原有队列。
    _collectWatch();
    _counting = false;
    unawaited(_report(force: true, eventType: 'PAUSE'));
    unawaited(_wakeLock.disable());
    _player?.removeListener(_onPlayerChanged);
    _player?.dispose();
    _player = null;
    setState(() {
      _loading = true;
      _playing = false;
      _commandBusy = false;
      _endReported = false;
      _seeking = false;
      _gestureIcon = null;
      _speed = 1.0;
      _confirmedSpeed = 1.0;
      _error = null;
    });
    await _load();
  }

  /// 命令串行化，快速连点不会让原生暂停、跳转与倍速乱序。
  Future<void> _runCommand(Future<void> Function() action) async {
    if (_loading || _commandBusy || _player == null) return;
    final player = _player;
    _commandBusy = true;
    try {
      await action();
    } catch (_) {
      if (mounted && identical(_player, player)) {
        _collectWatch();
        _counting = false;
        setState(() {
          _error = '播放操作失败，请重试';
          _playing = false;
        });
        unawaited(_wakeLock.disable());
      }
    } finally {
      // 旧内核迟到结束不能解开新一轮操作的锁。
      if (identical(_player, player)) _commandBusy = false;
    }
  }

  /// 跳转立即展示方向及等待动画；真实位置更新后恢复计时，超时转为可重试错误。
  Future<void> _seekTo(int position) async {
    await _runCommand(() async {
      final player = _player!;
      final destination = position.clamp(0, _durationMs);
      _collectWatch();
      _counting = false;
      setState(() {
        _seeking = true;
        _bufferingLabel = destination > player.positionMs
            ? '正在快进'
            : destination < player.positionMs
            ? '正在快退'
            : '正在跳转';
      });
      try {
        await player.seek(destination).timeout(const Duration(seconds: 8));
        if (!mounted || !identical(_player, player)) return;
        _seeking = false;
        _onPlayerChanged();
        unawaited(_report(force: true));
      } finally {
        if (mounted && identical(_player, player)) {
          setState(() => _seeking = false);
        }
      }
    });
  }

  void _seekBy(int deltaMs) => unawaited(_seekTo(_positionMs + deltaMs));

  void _togglePlay() => unawaited(
    _runCommand(() async {
      if (_playing) {
        await _pause();
      } else {
        final player = _player!;
        if (_positionMs >= _durationMs) {
          await player.seek(0).timeout(const Duration(seconds: 8));
        }
        if (!mounted || !identical(_player, player)) return;
        await player.play().timeout(const Duration(seconds: 8));
        if (!mounted || !identical(_player, player)) return;
        _onPlayerChanged();
        if (!_foreground) await _pause();
        _reporter ??= Timer.periodic(
          _reportInterval,
          (_) => unawaited(_report()),
        );
      }
    }),
  );

  /// 计时只取真实前台播放经过的时间，缓冲、暂停、拖动不计入观看时长。
  void _collectWatch() {
    final elapsed = _time();
    final delta = _counting ? elapsed - _watchSample : 0;
    _watchSample = elapsed;
    _sessionWatchedMs += delta;
    _uncommittedMs += delta;
  }

  void _onPlayerChanged() {
    if (!mounted || _player == null) return;
    _collectWatch();
    final player = _player!;
    final active =
        player.playing &&
        !player.buffering &&
        player.error == null &&
        _foreground &&
        !_seeking;
    _counting = active;
    if (!player.ended) _endReported = false;
    final reachedEnd = player.ended && !_endReported;
    if (reachedEnd) _endReported = true;
    final wasPlaying = _playing;
    setState(() {
      _playing = player.playing && player.error == null && _foreground;
      if (!_seeking) _positionMs = player.positionMs;
      _error = player.error;
      if (!_seeking && !player.buffering) _bufferingLabel = '正在缓冲';
    });
    if (_playing != wasPlaying) {
      _revealControls();
      unawaited(_playing ? _wakeLock.enable() : _wakeLock.disable());
      if (!_playing && !_pausing && !reachedEnd) {
        unawaited(_report(force: true, eventType: 'PAUSE'));
      }
    }
    if (reachedEnd) unawaited(_report(force: true, eventType: 'PAUSE'));
  }

  Future<void> _pause() async {
    _collectWatch();
    _counting = false;
    final player = _player;
    _pausing = true;
    try {
      await player?.pause().timeout(const Duration(seconds: 8));
    } catch (_) {
      // 后台暂停由生命周期直接触发，失败也须释放常亮、停止计时并保留重试入口。
      if (mounted && identical(_player, player)) {
        setState(() => _error = '暂停失败，请重新加载');
      }
    } finally {
      if (identical(_player, player)) _pausing = false;
    }
    if (!mounted || !identical(_player, player)) return;
    setState(() => _playing = false);
    await _wakeLock.disable();
    // 上报队列负责网络重试，不占用播放命令锁。
    unawaited(_report(force: true, eventType: 'PAUSE'));
  }

  Future<void> _report({
    bool force = false,
    String eventType = 'PROGRESS',
  }) async {
    // 未取得待办或媒体时没有可回填的学习事件，退出错误页不制造空进度。
    if (_todo == null) return;
    _collectWatch();
    if (!force && _uncommittedMs == 0) return;
    final delta = _uncommittedMs;
    _uncommittedMs = 0;
    // 片尾按内核精确时长补报；仅校正 Emby 与内核两秒以内的时长尾差。
    // 达标仍由服务端裁决，观看时长不因校正增加。
    var reportedPosition = _positionMs;
    final catalogDuration = _todo?.resourceDurationMs;
    if (_player?.ended == true &&
        catalogDuration != null &&
        catalogDuration >= reportedPosition &&
        catalogDuration - reportedPosition <= 2000) {
      reportedPosition = catalogDuration;
    }
    final result = await _queue.submit(
      positionMs: reportedPosition,
      deltaWatchedMs: delta,
      foreground: true,
      eventType: eventType,
    );
    if (result != null && mounted) {
      final newlyCompleted = !_completed && result.completed;
      setState(() {
        _progressPermille = result.progressPermille;
        _completed = result.completed;
      });
      if (newlyCompleted && _playing) {
        ShanganFeedback.show(context, '已达到目标进度，这条待办已完成');
      }
    }
  }

  Future<void> _openCompleteSheet(TodoItem todo) async {
    await _pause();
    if (!mounted) return;
    await CompleteSheet.show(context, todo: todo);
  }

  Future<void> _markComplete(TodoItem todo) async {
    await _report(force: true);
    if (!mounted) return;
    final done = await CompleteSheet.show(context, todo: todo);
    if (done && mounted) {
      setState(() => _completed = true);
    }
  }

  /// 目标对应的时间点；无目标或缺时长时返回 null。
  int? _targetMs(TodoItem todo) {
    final target = todo.targetProgressPermille;
    if (target == null || _durationMs == 0) return null;
    return (_durationMs * target / 1000).round();
  }

  int? _remainingMs(TodoItem todo) {
    final targetMs = _targetMs(todo);
    if (targetMs == null) return null;
    return (targetMs - _positionMs).clamp(0, targetMs);
  }

  static final _smallButtonStyle = OutlinedButton.styleFrom(
    minimumSize: const Size(0, 44),
    padding: const EdgeInsets.symmetric(horizontal: 12),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700),
  );

  /// 原型 3-1「调整目标」：改这条 Todo 的目标千分比，由服务端重新裁决。
  Future<void> _adjustTarget(TodoItem todo) async {
    final picked = await showShanganSheet(
      context,
      heightFactor: 0.45,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const ShanganSheetHeader(
                title: '调整完成标准',
                subtitle: '达到即算今日完成，是否达标由服务端裁决。',
              ),
              const SizedBox(height: 14),
              for (final permille in const [300, 500, 800, 1000])
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: OutlinedButton(
                    onPressed: () {
                      _pendingTarget = permille;
                      Navigator.of(sheetContext).pop(true);
                    },
                    child: Text(
                      permille == 1000 ? '全部看完' : '看到 ${permille ~/ 10}%',
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
    final target = _pendingTarget;
    if (!picked || target == null) return;
    _pendingTarget = null;
    try {
      await ref
          .read(shanganRepositoryProvider)
          .patchTodo(todo.id, targetProgressPermille: target);
      await _load();
    } catch (error) {
      if (mounted) {
        ShanganFeedback.show(context, '调整目标失败：$error', error: true);
      }
    }
  }

  int? _pendingTarget;
}

/// 双击动作提示：立即出现，轻微放大后淡出；不影响播放及点击热区。
final class _PlayerGestureFeedback extends StatelessWidget {
  const _PlayerGestureFeedback({
    super.key,
    required this.icon,
    required this.label,
  });
  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
    tween: Tween(begin: 0, end: 1),
    duration: const Duration(milliseconds: 650),
    builder: (context, value, child) => Opacity(
      opacity: value < 0.55 ? 1 : ((1 - value) / 0.45).clamp(0.0, 1.0),
      child: Transform.scale(scale: 0.9 + value * 0.2, child: child),
    ),
    child: Container(
      width: 88,
      height: 88,
      decoration: const BoxDecoration(
        color: Color(0xB3000000),
        shape: BoxShape.circle,
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, color: Colors.white, size: 36),
          const SizedBox(height: 4),
          Text(
            label,
            style: const TextStyle(color: Colors.white, fontSize: 12),
          ),
        ],
      ),
    ),
  );
}

/// 白色图标共享操作层遮罩；透明热区至少 44pt，避免小图标难以点击。
final class _VideoButton extends StatelessWidget {
  const _VideoButton({
    required this.icon,
    required this.onTap,
    this.semanticLabel,
  });

  final IconData icon;
  final VoidCallback onTap;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    const size = 44.0;
    return Semantics(
      button: true,
      label: semanticLabel,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: SizedBox(
          width: size,
          height: size,
          child: Center(child: Icon(icon, color: Colors.white, size: 26)),
        ),
      ),
    );
  }
}

/// 轻量文字工具，保持足够点击区域且不绘制胶囊背景。
final class _VideoTool extends StatelessWidget {
  const _VideoTool({required this.label, required this.onTap});
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    child: GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Container(
        constraints: const BoxConstraints(minHeight: 44, minWidth: 44),
        alignment: Alignment.center,
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Text(
          label,
          maxLines: 1,
          style: const TextStyle(
            fontSize: 14,
            color: Colors.white,
            fontWeight: FontWeight.w500,
          ),
        ),
      ),
    ),
  );
}

/// 可自由拖动的细进度条，目标信息由画面外的进度卡展示。
final class _Timeline extends StatelessWidget {
  const _Timeline({
    required this.positionMs,
    required this.durationMs,
    required this.onChanged,
    required this.onChangeEnd,
  });

  final int positionMs;
  final int durationMs;
  final ValueChanged<int> onChanged;
  final VoidCallback onChangeEnd;

  @override
  Widget build(BuildContext context) {
    final ratio = durationMs == 0
        ? 0.0
        : (positionMs / durationMs).clamp(0.0, 1.0);
    // 目标信息保留在下方进度卡，不在时间旁额外绘制刻度。
    return SizedBox(
      height: 44,
      child: SliderTheme(
        data: SliderThemeData(
          trackHeight: 2,
          activeTrackColor: Colors.white,
          inactiveTrackColor: Colors.white.withValues(alpha: 0.42),
          thumbColor: Colors.white,
          overlayColor: Colors.white24,
          thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 4),
          trackShape: const RoundedRectSliderTrackShape(),
        ),
        child: Slider(
          value: ratio,
          onChanged: durationMs == 0
              ? null
              : (value) => onChanged((value * durationMs).round()),
          onChangeEnd: (_) => onChangeEnd(),
        ),
      ),
    );
  }
}
