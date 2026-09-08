import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

/// 当前登录用户的只读身份快照。
final class UserProfile {
  const UserProfile({
    required this.id,
    required this.username,
    required this.displayName,
    required this.roles,
    required this.timezone,
    this.supervisorMode = false,
    this.learnerCount = 0,
  });

  final String id;
  final String username;
  final String displayName;

  /// 账号可同时具备 LEARNER 与 SUPERVISOR。
  final List<String> roles;
  final String timezone;

  /// 是否绑定了学员，决定「我的」页是否出现督学端入口。
  final bool supervisorMode;
  final int learnerCount;

  bool get isSupervisor => supervisorMode || roles.contains('SUPERVISOR');

  factory UserProfile.fromJson(Map<String, dynamic> json) {
    final rawRoles = json['roles'];
    final roles = <String>[
      if (rawRoles is List) ...rawRoles.map((role) => role.toString()),
      if (rawRoles == null && json['role'] != null) json['role'].toString(),
    ];
    return UserProfile(
      id: json['id'] as String,
      username: json['username'] as String,
      displayName: json['displayName'] as String? ?? json['username'] as String,
      roles: roles.isEmpty ? const ['LEARNER'] : roles,
      timezone: json['timezone'] as String? ?? 'Asia/Shanghai',
      supervisorMode: json['supervisorMode'] as bool? ?? false,
      learnerCount: (json['learnerCount'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 认证失败的稳定异常，供控制器统一回到登录页。
final class AuthException implements Exception {
  const AuthException._(this.message);

  const AuthException.unauthorized() : this._('登录状态已失效，请重新登录');

  const AuthException.roleUnavailable() : this._('账号不具备所选身份，请重新选择');

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
