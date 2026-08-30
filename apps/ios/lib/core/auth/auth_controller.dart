import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

enum AuthStatus { initializing, unauthenticated, authenticating, authenticated }

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
  }) {
    return AuthController._(repository, tokenStore);
  }

  AuthController._(this._repository, this._tokenStore);

  final AuthRepository _repository;
  final TokenStore _tokenStore;

  AuthState _state = const AuthState.initializing();
  AuthState get state => _state;

  Future<void> initialize() async {
    final tokens = await _tokenStore.read();
    if (tokens == null) {
      _setState(const AuthState(status: AuthStatus.unauthenticated));
      return;
    }
    try {
      await loadCurrentUser();
    } on ApiException catch (exception) {
      _setState(
        AuthState(
          status: AuthStatus.unauthenticated,
          message: exception.message,
        ),
      );
    } catch (_) {
      // AuthException 已由 loadCurrentUser 清理；其他异常使用安全的兜底文案。
      if (_state.status == AuthStatus.initializing) {
        _setState(
          const AuthState(
            status: AuthStatus.unauthenticated,
            message: '暂时无法恢复登录状态，请重新登录',
          ),
        );
      }
    }
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
