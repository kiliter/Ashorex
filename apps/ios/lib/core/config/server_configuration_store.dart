import 'package:shared_preferences/shared_preferences.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';

/// 服务端地址持久化边界；这里只允许保存非敏感连接配置。
abstract interface class ServerConfigurationStore {
  Future<ServerConfiguration> load({required String defaultBaseUrl});

  Future<void> save(ServerConfiguration configuration);
}

/// 使用 SharedPreferences 保存用户覆盖地址，业务数据仍全部来自服务端。
final class SharedPreferencesServerConfigurationStore
    implements ServerConfigurationStore {
  SharedPreferencesServerConfigurationStore._(this._preferences);

  static const preferenceKey = 'shangan.server.base_url';

  final SharedPreferences _preferences;

  static Future<SharedPreferencesServerConfigurationStore> create() async {
    return SharedPreferencesServerConfigurationStore._(
      await SharedPreferences.getInstance(),
    );
  }

  @override
  Future<ServerConfiguration> load({required String defaultBaseUrl}) async {
    final saved = _preferences.getString(preferenceKey);
    return ServerConfiguration.parse(
      saved == null || saved.trim().isEmpty ? defaultBaseUrl : saved,
    );
  }

  @override
  Future<void> save(ServerConfiguration configuration) async {
    final saved = await _preferences.setString(
      preferenceKey,
      configuration.baseUrl,
    );
    if (!saved) {
      throw StateError('服务端地址保存失败');
    }
  }
}
