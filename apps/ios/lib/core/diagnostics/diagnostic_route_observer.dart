import 'package:flutter/widgets.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log_redactor.dart';

/// 记录页面进入，查询串经脱敏后才写入。
final class DiagnosticRouteObserver extends NavigatorObserver {
  DiagnosticRouteObserver({this.redactor = const DiagnosticLogRedactor()});

  final DiagnosticLogRedactor redactor;

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    _log('push', route, previousRoute);
  }

  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) {
    _log('replace', newRoute, oldRoute);
  }

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    _log('pop', previousRoute, route);
  }

  void _log(String action, Route<dynamic>? current, Route<dynamic>? previous) {
    DiagnosticLog.info('nav', action, {
      'to': _name(current),
      'from': _name(previous),
    });
  }

  String _name(Route<dynamic>? route) {
    final settings = route?.settings;
    if (settings == null) return '-';
    final name = settings.name;
    if (name == null || name.isEmpty) return settings.runtimeType.toString();
    return redactor.redact(name);
  }
}
