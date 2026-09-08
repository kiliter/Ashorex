import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:shangan_ios/core/data/shangan_repository.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';

/// 心跳上报服务。
///
/// 间隔由服务端下发；心跳失败不影响本地播放与计时，只影响服务端在线判定。
/// 收到 `pendingNagId` 时通过回调通知外层拉起全屏催办。
final class HeartbeatService with WidgetsBindingObserver {
  HeartbeatService({
    required this.repository,
    required this.clientVersion,
    this.onPendingNag,
    this.onTransportMode,
    this.onConnected,
    this.queueDepth,
  });

  /// 数据仓库；心跳只调用一个接口。
  final ShanganRepository repository;

  /// 客户端版本，随心跳上报便于后台排查。
  final String clientVersion;

  /// 收到待回应催办时回调；参数为催办 ID。
  final void Function(String nagId)? onPendingNag;

  /// 每次成功心跳同步通知模式，旧服务端缺少字段时恢复原轮询。
  final void Function(String mode)? onTransportMode;

  /// 心跳恢复后补发已落盘进度，不依赖播放页仍然存在。
  final Future<void> Function()? onConnected;
  final int Function()? queueDepth;

  Timer? _timer;
  Duration _interval = const Duration(seconds: 60);
  bool _foreground = true;
  bool _disposed = false;
  bool _online = true;
  int _pendingQueuedEvents = 0;
  DateTime? _lastSuccessAt;

  /// 最近一次心跳是否成功；用于首页离线条。
  bool get online => _online;

  /// 最近一次心跳成功的时刻；「我的」页展示「N 秒前上报」。
  DateTime? get lastSuccessAt => _lastSuccessAt;

  int get queuedEvents => queueDepth?.call() ?? _pendingQueuedEvents;

  void start() {
    WidgetsBinding.instance.addObserver(this);
    _schedule();
    unawaited(_beat());
  }

  void dispose() {
    _disposed = true;
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _timer = null;
  }

  /// 由进度队列告知当前待同步事件数，随心跳一起上报。
  void reportQueueDepth(int depth) {
    _pendingQueuedEvents = depth;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    unawaited(_beat());
  }

  void _schedule() {
    _timer?.cancel();
    _timer = Timer.periodic(_interval, (_) => unawaited(_beat()));
  }

  Future<void> _beat() async {
    if (_disposed) return;
    try {
      final HeartbeatResult result = await repository.heartbeat(
        foreground: _foreground,
        clientVersion: clientVersion,
        queuedEvents: queueDepth?.call() ?? _pendingQueuedEvents,
      );
      // 注销期间完成的旧请求不得重建定时器或恢复 SSE。
      if (_disposed) return;
      _online = true;
      _lastSuccessAt = DateTime.now();
      if (onConnected != null) unawaited(onConnected!());
      _pendingQueuedEvents = queueDepth?.call() ?? _pendingQueuedEvents;
      final next = Duration(seconds: result.heartbeatIntervalSeconds);
      if (next != _interval) {
        _interval = next;
        _schedule();
      }
      onTransportMode?.call(result.nagTransportMode);
      final nagId = result.pendingNagId;
      if (nagId != null && onPendingNag != null) {
        onPendingNag!(nagId);
      }
    } catch (_) {
      // 心跳失败只标记离线，不影响本地播放与计时。
      _online = false;
    }
  }
}
