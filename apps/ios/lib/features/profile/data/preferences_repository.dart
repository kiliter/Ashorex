import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 用户可在 App 中维护的服务端偏好。
final class UserPreferences {
  const UserPreferences({
    required this.timezone,
    required this.aliveCheckEnabled,
    required this.aliveCheckIntervalPercent,
    required this.dayEndLocalTime,
  });

  final String timezone;
  final bool aliveCheckEnabled;
  final int aliveCheckIntervalPercent;
  final String dayEndLocalTime;

  factory UserPreferences.fromJson(Map<String, dynamic> json) {
    return UserPreferences(
      timezone: json['timezone'] as String,
      aliveCheckEnabled: json['aliveCheckEnabled'] as bool,
      aliveCheckIntervalPercent: (json['aliveCheckIntervalPercent'] as num)
          .toInt(),
      dayEndLocalTime: json['dayEndLocalTime'] as String,
    );
  }
}

/// 偏好设置 API 边界，页面不直接依赖 Dio。
abstract interface class PreferencesRepository {
  Future<UserPreferences> load();

  Future<UserPreferences> update(UserPreferences preferences);
}

/// 将偏好设置读写交给服务端保存和校验。
final class RemotePreferencesRepository implements PreferencesRepository {
  RemotePreferencesRepository(this._api);

  final ApiClient _api;

  @override
  Future<UserPreferences> load() async {
    return UserPreferences.fromJson(await _api.getJson('/api/v1/preferences'));
  }

  @override
  Future<UserPreferences> update(UserPreferences preferences) async {
    final json = await _api.putJson(
      '/api/v1/preferences',
      data: {
        'timezone': preferences.timezone,
        'aliveCheckEnabled': preferences.aliveCheckEnabled,
        'aliveCheckIntervalPercent': preferences.aliveCheckIntervalPercent,
        'dayEndLocalTime': preferences.dayEndLocalTime,
      },
    );
    return UserPreferences.fromJson(json);
  }
}

/// 由 bootstrap 注入真实仓库，Widget 测试可覆盖为内存实现。
final preferencesRepositoryProvider = Provider<PreferencesRepository>((ref) {
  throw StateError('PreferencesRepository 尚未注入');
});
