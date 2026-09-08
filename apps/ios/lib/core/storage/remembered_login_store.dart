import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 用户主动选择记住的表单凭据，不代表有效登录会话。
final class RememberedLogin {
  const RememberedLogin(this.username, this.password);
  final String username;
  final String password;
}

/// 按服务器 Origin 隔离，避免向另一服务器回填账号密码。
class RememberedLoginStore {
  RememberedLoginStore({FlutterSecureStorage? storage})
    : _storage = storage ?? const FlutterSecureStorage();
  final FlutterSecureStorage _storage;

  String _key(String origin) =>
      'shangan.remembered_login.${Uri.encodeComponent(origin)}';

  /// 仅从系统安全存储读取，不写日志或普通配置文件。
  Future<RememberedLogin?> read(String origin) async {
    final raw = await _storage.read(key: _key(origin));
    if (raw == null) return null;
    final json = jsonDecode(raw) as Map<String, dynamic>;
    return RememberedLogin(
      json['username'] as String,
      json['password'] as String,
    );
  }

  /// 空值表示用户取消记住，立即删除对应服务器的凭据。
  Future<void> write(String origin, RememberedLogin? value) async {
    if (value == null) {
      await _storage.delete(key: _key(origin));
    } else {
      await _storage.write(
        key: _key(origin),
        value: jsonEncode({
          'username': value.username,
          'password': value.password,
        }),
      );
    }
  }
}

final rememberedLoginStoreProvider = Provider<RememberedLoginStore>(
  (ref) => RememberedLoginStore(),
);
