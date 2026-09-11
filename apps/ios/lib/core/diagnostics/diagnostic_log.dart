import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log_store.dart';

/// 本机诊断日志入口。未安装时所有调用为空操作，避免测试与启动失败互相干扰。
final class DiagnosticLog {
  DiagnosticLog._();

  static DiagnosticLogStore? _store;
  static _LifecycleProbe? _lifecycle;
  static FlutterExceptionHandler? _previousFlutterError;
  static ErrorCallback? _previousPlatformError;

  /// 在应用私有目录打开滚动日志，并接管 FlutterError / 生命周期。
  static Future<void> install({Directory? directory}) async {
    if (_store != null) return;
    try {
      final dir =
          directory ??
          Directory('${(await getApplicationSupportDirectory()).path}/logs');
      final store = DiagnosticLogStore(directory: dir);
      await store.open();
      _store = store;
      _previousFlutterError = FlutterError.onError;
      FlutterError.onError = (details) {
        error(
          'flutter',
          details.exceptionAsString(),
          error: details.exception,
          stack: details.stack,
        );
        _previousFlutterError?.call(details);
      };
      _previousPlatformError = PlatformDispatcher.instance.onError;
      PlatformDispatcher.instance.onError = (error, stack) {
        DiagnosticLog.error(
          'zone',
          error.toString(),
          error: error,
          stack: stack,
        );
        return _previousPlatformError?.call(error, stack) ?? false;
      };
      _lifecycle = _LifecycleProbe();
      WidgetsBinding.instance.addObserver(_lifecycle!);
      info('boot', 'app start', {
        'platform': defaultTargetPlatform.name,
        'engine': const String.fromEnvironment(
          'PLAYBACK_ENGINE',
          defaultValue: 'default',
        ),
      });
    } catch (error, stack) {
      debugPrint('诊断日志初始化失败: $error\n$stack');
    }
  }

  static void info(
    String category,
    String message, [
    Map<String, Object?>? data,
  ]) {
    _write('INFO', category, message, data: data);
  }

  static void warn(
    String category,
    String message, [
    Map<String, Object?>? data,
  ]) {
    _write('WARN', category, message, data: data);
  }

  static void error(
    String category,
    String message, {
    Object? error,
    StackTrace? stack,
    Map<String, Object?>? data,
  }) {
    final merged = <String, Object?>{...?data};
    if (error != null) merged['error'] = error.toString();
    _write('ERROR', category, message, data: merged, stack: stack);
  }

  /// 上报前取出当前文件与上一份轮转备份。
  static Future<DiagnosticLogSnapshot> snapshot() async {
    final store = _store;
    if (store == null) {
      return const DiagnosticLogSnapshot(
        bytes: [],
        lastModified: null,
        directoryPath: '',
      );
    }
    return store.snapshot();
  }

  /// 测试结束后拆掉全局钩子，避免污染后续用例。
  static Future<void> resetForTest() async {
    if (_lifecycle != null) {
      WidgetsBinding.instance.removeObserver(_lifecycle!);
      _lifecycle = null;
    }
    if (_previousFlutterError != null) {
      FlutterError.onError = _previousFlutterError;
      _previousFlutterError = null;
    }
    if (_previousPlatformError != null) {
      PlatformDispatcher.instance.onError = _previousPlatformError;
      _previousPlatformError = null;
    }
    await _store?.close();
    _store = null;
  }

  static void _write(
    String level,
    String category,
    String message, {
    Map<String, Object?>? data,
    StackTrace? stack,
  }) {
    final store = _store;
    if (store == null) return;
    final time = DateTime.now().toUtc().toIso8601String();
    final buffer = StringBuffer('$time $level  [$category] $message');
    if (data != null && data.isNotEmpty) {
      data.forEach((key, value) {
        if (value == null) return;
        buffer.write(' $key=${_scalar(value)}');
      });
    }
    if (stack != null) {
      final frames = stack.toString().split('\n').take(40).join(' | ');
      if (frames.isNotEmpty) buffer.write(' stack=$frames');
    }
    var line = buffer.toString();
    if (line.length > 16 * 1024) {
      line = '${line.substring(0, 16 * 1024)}…';
    }
    store.append(line);
    if (kDebugMode) {
      debugPrint(line);
    }
  }

  static String _scalar(Object value) {
    final text = value.toString().replaceAll('\n', ' ');
    if (text.contains(' ') || text.contains('=')) {
      return '"$text"';
    }
    return text;
  }
}

final class _LifecycleProbe with WidgetsBindingObserver {
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    DiagnosticLog.info('lifecycle', state.name);
  }
}
