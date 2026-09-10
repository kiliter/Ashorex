import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

enum AuthStatus {
  initializing,
  unauthenticated,
  authenticating,
  authenticated,
  serviceUnavailable,
}

/// 认证页面需要的最小状态，不在本地保存任何业务真相。
final class AuthState {
  const AuthState({required this.status, this.user, this.message});

  const AuthState.initializing() : this(status: AuthStatus.initializing);

  final AuthStatus status;
  final UserProfile? user;
  final String? message;
}

/// 认证控制器管理 Token 生命周期和登录态，并作为路由刷新信号。
final class AuthController extends ChangeNotifier {
  factory AuthController({
    required AuthRepository repository,
    required TokenStore tokenStore,
    SessionRoleStore? roleStore,
    Duration restoreTimeout = const Duration(seconds: 8),
  }) {
    return AuthController._(repository, tokenStore, restoreTimeout, roleStore);
  }

  AuthController._(
    this._repository,
    this._tokenStore,
    this._restoreTimeout,
    this._roleStore,
  );

  final AuthRepository _repository;
  final TokenStore _tokenStore;
  final Duration _restoreTimeout;
  final SessionRoleStore? _roleStore;
  String _sessionRole = 'LEARNER';

  /// 本次登录固定的身份，页面不能直接修改。
  bool get isSupervisorSession => _sessionRole == 'SUPERVISOR';
  int _restoreGeneration = 0;

  AuthState _state = const AuthState.initializing();
  AuthState get state => _state;

  Future<void> initialize() async {
    final generation = ++_restoreGeneration;
    final tokens = await _tokenStore.read();
    _sessionRole = await _roleStore?.readRole() ?? 'LEARNER';
    if (tokens == null) {
      DiagnosticLog.info('auth', 'restore skipped, no token');
      _setState(const AuthState(status: AuthStatus.unauthenticated));
      return;
    }
    try {
      final user = await _repository.loadCurrentUser().timeout(_restoreTimeout);
      if (generation != _restoreGeneration) return;
      _validateRole(user);
      DiagnosticLog.info('auth', 'restore ok', {
        'userId': user.id,
        'username': user.username,
        'role': _sessionRole,
      });
      _setState(AuthState(status: AuthStatus.authenticated, user: user));
    } on AuthException catch (exception) {
      if (generation != _restoreGeneration) return;
      await _tokenStore.clear();
      await _roleStore?.writeRole(null);
      _setState(
        AuthState(
          status: AuthStatus.unauthenticated,
          message: exception.message,
        ),
      );
    } on TimeoutException {
      if (generation != _restoreGeneration) return;
      _setServiceUnavailable();
    } on ApiException {
      if (generation != _restoreGeneration) return;
      _setServiceUnavailable();
    } catch (_) {
      if (generation != _restoreGeneration) return;
      _setServiceUnavailable();
    }
  }

  /// 服务恢复页主动重试时复用本地 Token，不要求用户重新输入账号密码。
  Future<void> retryConnection() async {
    _setState(const AuthState(status: AuthStatus.initializing));
    await initialize();
  }

  void _setServiceUnavailable() {
    _setState(
      const AuthState(
        status: AuthStatus.serviceUnavailable,
        message: '暂时无法连接服务端，本机登录凭据已保留',
      ),
    );
  }

  Future<UserProfile> loadCurrentUser() async {
    try {
      final user = await _repository.loadCurrentUser();
      _validateRole(user);
      _setState(AuthState(status: AuthStatus.authenticated, user: user));
      return user;
    } on AuthException catch (exception) {
      await _tokenStore.clear();
      await _roleStore?.writeRole(null);
      _setState(
        AuthState(
          status: AuthStatus.unauthenticated,
          message: exception.message,
        ),
      );
      rethrow;
    }
  }

  /// 登录前选定身份，服务端身份快照校验通过后才进入对应页面。
  Future<void> login(
    String username,
    String password, {
    String role = 'LEARNER',
  }) async {
    _sessionRole = role;
    _setState(const AuthState(status: AuthStatus.authenticating));
    try {
      final tokens = await _repository.login(username.trim(), password);
      await _tokenStore.write(tokens);
      final user = await _repository.loadCurrentUser();
      _validateRole(user);
      await _roleStore?.writeRole(_sessionRole);
      DiagnosticLog.info('auth', 'login ok', {
        'userId': user.id,
        'username': user.username,
        'role': _sessionRole,
      });
      _setState(AuthState(status: AuthStatus.authenticated, user: user));
    } on ApiException catch (exception) {
      DiagnosticLog.warn('auth', 'login failed', {
        'username': username.trim(),
        'errorCode': exception.errorCode,
      });
      _setState(
        AuthState(
          status: AuthStatus.unauthenticated,
          message: exception.message,
        ),
      );
      rethrow;
    } on AuthException catch (exception) {
      await _tokenStore.clear();
      await _roleStore?.writeRole(null);
      _setState(
        AuthState(
          status: AuthStatus.unauthenticated,
          message: exception.message,
        ),
      );
      rethrow;
    }
  }

  Future<void> logout() async {
    DiagnosticLog.info('auth', 'logout');
    final tokens = await _tokenStore.read();
    try {
      if (tokens != null) {
        await _repository.logout(tokens.refreshToken);
      }
    } finally {
      await handleAuthenticationLost();
    }
  }

  /// Dio 判定登录彻底失效后，清理 Token 并通知 go_router 回登录页。
  Future<void> handleAuthenticationLost() async {
    DiagnosticLog.warn('auth', 'session lost');
    await _tokenStore.clear();
    await _roleStore?.writeRole(null);
    _setState(const AuthState(status: AuthStatus.unauthenticated));
  }

  /// 本地选择不授予权限，身份不匹配时拒绝本次登录。
  void _validateRole(UserProfile user) {
    if (isSupervisorSession
        ? !user.isSupervisor
        : _sessionRole != 'LEARNER' || !user.roles.contains('LEARNER')) {
      throw const AuthException.roleUnavailable();
    }
  }

  void _setState(AuthState next) {
    _state = next;
    notifyListeners();
  }
}

/// 由 bootstrap 注入真实控制器，测试可覆盖为内存实现。
final authControllerProvider = Provider<AuthController>((ref) {
  throw StateError('AuthController 尚未注入');
});
