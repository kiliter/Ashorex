import 'dart:async';
import 'package:flutter/widgets.dart';

/// 前台 SSE 生命周期：模式由心跳下发，断线退避重连，后台与注销时释放连接。
final class NagEventService with WidgetsBindingObserver {
  NagEventService({required this.connect, required this.onPendingNag});
  final Stream<String> Function() connect;
  final void Function() onPendingNag;
  StreamSubscription<String>? _subscription;
  Timer? _retry;
  bool _enabled = false;
  bool _foreground = true;
  bool _disposed = false;
  int _generation = 0;
  int _retrySeconds = 1;

  /// 注册观察者；首次心跳确认服务端支持 SSE 后才开始连接。
  void start() {
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
  }

  /// 保存后台配置后，下一次心跳即可切换，不依赖重启 App。
  void setMode(String mode) {
    if (_disposed) return;
    final enabled = mode == 'SSE';
    if (_enabled == enabled) return;
    _enabled = enabled;
    _stop();
    if (_enabled && _foreground) _open();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final foreground = state == AppLifecycleState.resumed;
    if (_foreground == foreground || _disposed) return;
    _foreground = foreground;
    _stop();
    if (_foreground && _enabled) _open();
  }

  /// 每个连接带代次，旧连接的异步回调不能影响新连接。
  void _open() {
    if (_disposed || !_enabled || !_foreground) return;
    final generation = ++_generation;
    try {
      _subscription = connect().listen(
        (_) {
          if (generation != _generation || _disposed) return;
          _retrySeconds = 1;
          onPendingNag();
        },
        onError: (Object error) => _reconnect(generation),
        onDone: () => _reconnect(generation),
        cancelOnError: true,
      );
    } catch (_) {
      _reconnect(generation);
    }
  }

  /// 1、2、4……30 秒退避；恢复连接收到 ready 后重置并补查漏消息。
  void _reconnect(int generation) {
    if (generation != _generation || _disposed || !_enabled || !_foreground) {
      return;
    }
    _stop();
    _retry = Timer(Duration(seconds: _retrySeconds), _open);
    _retrySeconds = (_retrySeconds * 2).clamp(1, 30);
  }

  void _stop() {
    ++_generation;
    _retry?.cancel();
    _retry = null;
    final subscription = _subscription;
    _subscription = null;
    if (subscription != null) unawaited(subscription.cancel());
  }

  /// 外壳销毁包含注销与切换服务端场景，不保留旧账号连接。
  void dispose() {
    _disposed = true;
    _stop();
    WidgetsBinding.instance.removeObserver(this);
  }
}
