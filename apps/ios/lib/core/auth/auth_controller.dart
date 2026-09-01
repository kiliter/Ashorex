import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
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
    Duration restoreTimeout = const Duration(seconds: 8),
  }) {
    return AuthController._(repository, tokenStore, restoreTimeout);
  }

  AuthController._(this._repository, this._tokenStore, this._restoreTimeout);

  final AuthRepository _repository;
  final TokenStore _tokenStore;
  final Duration _restoreTimeout;
  int _restoreGeneration = 0;

  AuthState _state = const AuthState.initializing();
  AuthState get state => _state;

  Future<void> initialize() async {
    final generation = ++_restoreGeneration;
    final tokens = await _tokenStore.read();
    if (tokens == null) {
      _setState(const AuthState(status: AuthStatus.unauthenticated));
      return;
    }
    try {
      final user = await _repository.loadCurrentUser().timeout(_restoreTimeout);
      if (generation != _restoreGeneration) return;
      _setState(AuthState(status: AuthStatus.authenticated, user: user));
    } on AuthException catch (exception) {
      if (generation != _restoreGeneration) return;
      await _tokenStore.clear();
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
      _setState(AuthState(status: AuthStatus.authenticated, user: user));
      return user;
    } on AuthException catch (exception) {
      await _tokenStore.clear();
      _setState(
        AuthState(
          status: AuthStatus.unauthenticated,
          message: exception.message,
        ),
      );
      rethrow;
    }
  }

  Future<void> login(String username, String password) async {
    _setState(const AuthState(status: AuthStatus.authenticating));
    try {
      final tokens = await _repository.login(username.trim(), password);
      await _tokenStore.write(tokens);
      await loadCurrentUser();
    } on ApiException catch (exception) {
      _setState(
        AuthState(
          status: AuthStatus.unauthenticated,
          message: exception.message,
        ),
      );
      rethrow;
    } on AuthException {
      rethrow;
    }
  }

  Future<void> logout() async {
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
    await _tokenStore.clear();
    _setState(const AuthState(status: AuthStatus.unauthenticated));
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
