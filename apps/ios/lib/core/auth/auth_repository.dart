import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

/// 当前登录用户的只读身份快照。
final class UserProfile {
  const UserProfile({
    required this.id,
    required this.username,
    required this.displayName,
    required this.role,
    required this.timezone,
  });

  final String id;
  final String username;
  final String displayName;
  final String role;
  final String timezone;

  factory UserProfile.fromJson(Map<String, dynamic> json) {
    return UserProfile(
      id: json['id'] as String,
      username: json['username'] as String,
      displayName: json['displayName'] as String,
      role: json['role'] as String,
      timezone: json['timezone'] as String,
    );
  }
}

/// 认证失败的稳定异常，供控制器统一回到登录页。
final class AuthException implements Exception {
  const AuthException._(this.message);

  const AuthException.unauthorized() : this._('登录状态已失效，请重新登录');

  final String message;

  @override
  String toString() => message;
}

/// 认证 API 边界；页面和控制器不直接调用 Dio。
abstract interface class AuthRepository {
  Future<TokenPair> login(String username, String password);

  Future<TokenPair> refresh(String refreshToken);

  Future<void> logout(String refreshToken);

  Future<UserProfile> loadCurrentUser();
}

/// 通过统一 ApiClient 调用服务端认证接口。
final class RemoteAuthRepository implements AuthRepository {
  RemoteAuthRepository(this._api);

  final ApiClient _api;

  @override
  Future<TokenPair> login(String username, String password) async {
    final json = await _api.postJson(
      '/api/v1/auth/login',
      data: {'username': username, 'password': password},
      skipAuthentication: true,
    );
    return TokenPair.fromJson(json);
  }

  @override
  Future<TokenPair> refresh(String refreshToken) async {
    final json = await _api.postJson(
      '/api/v1/auth/refresh',
      data: {'refreshToken': refreshToken},
      skipAuthentication: true,
    );
    return TokenPair.fromJson(json);
  }

  @override
  Future<void> logout(String refreshToken) async {
    await _api.postEmpty(
      '/api/v1/auth/logout',
      data: {'refreshToken': refreshToken},
      skipAuthentication: true,
    );
  }

  @override
  Future<UserProfile> loadCurrentUser() async {
    try {
      return UserProfile.fromJson(await _api.getJson('/api/v1/me'));
    } on ApiException catch (exception) {
      if (exception.statusCode == 401) {
        throw const AuthException.unauthorized();
      }
      rethrow;
    }
  }
}
