import 'package:flutter_riverpod/flutter_riverpod.dart';
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

/// 最近连接成功的服务器，只保存规范化地址，不包含账号或密码。
class ServerHistoryStore {
  static const key = 'shangan.server.history';

  Future<List<String>> read() async {
    final prefs = await SharedPreferences.getInstance();
    final result = <String>[];
    for (final value in prefs.getStringList(key) ?? <String>[]) {
      try {
        final origin = ServerConfiguration.parse(value).baseUrl;
        if (!result.contains(origin)) result.add(origin);
      } on FormatException {
        // 忽略旧版本或损坏的历史条目，不影响手动连接。
      }
    }
    return result;
  }

  /// 删除历史地址不切换当前连接，也不删除该服务器学习数据。
  Future<void> remove(String origin) async {
    final items = await read();
    final prefs = await SharedPreferences.getInstance();
    if (!await prefs.setStringList(
      key,
      items.where((item) => item != origin).toList(),
    )) {
      throw StateError('删除服务器历史失败');
    }
  }

  /// 当前服务器置顶并去重；地址历史不参与登录权限判断。
  Future<void> remember(ServerConfiguration configuration) async {
    final previous = await read();
    final prefs = await SharedPreferences.getInstance();
    if (!await prefs.setStringList(key, [
      configuration.baseUrl,
      ...previous.where((item) => item != configuration.baseUrl),
    ])) {
      throw StateError('服务器历史保存失败');
    }
  }
}

final serverHistoryStoreProvider = Provider<ServerHistoryStore>(
  (ref) => ServerHistoryStore(),
);
