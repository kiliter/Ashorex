import 'package:timezone/data/latest.dart' as timezone_data;
import 'package:timezone/timezone.dart' as timezone;

/// 账号 IANA 时区格式化工具。
///
/// API 与数据库继续保存 UTC；这里只在展示边界转换。设备时区可能与账号设置不同，
/// 因此不能直接依赖 [DateTime.toLocal]。
abstract final class AccountTime {
  static bool _initialized = false;

  /// 将绝对时间转换为账号时区；异常配置仅回退设备时区，避免一条脏数据阻断页面。
  static DateTime inTimezone(DateTime instant, String? timezoneName) {
    if (timezoneName == null || timezoneName.isEmpty) return instant.toLocal();
    try {
      if (!_initialized) {
        timezone_data.initializeTimeZones();
        _initialized = true;
      }
      return timezone.TZDateTime.from(
        instant.toUtc(),
        timezone.getLocation(timezoneName),
      );
    } on timezone.LocationNotFoundException {
      return instant.toLocal();
    }
  }

  /// 时间线统一使用两位小时和分钟。
  static String hourMinute(DateTime instant, String? timezoneName) {
    final local = inTimezone(instant, timezoneName);
    return '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }
}
