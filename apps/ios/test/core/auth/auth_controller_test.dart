import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

void main() {
  test('收到 401 后只刷新一次并重试原请求', () async {
    final tokenStore = MemoryTokenStore(
      const TokenPair(accessToken: 'expired-access', refreshToken: 'refresh-1'),
    );
    final repository = FakeAuthRepository();
    final controller = AuthController(
      repository: repository,
      tokenStore: tokenStore,
    );

    final user = await controller.loadCurrentUser();

    expect(user.id, 'user-1');
    expect(repository.refreshCalls, 1);
    expect(repository.currentUserCalls, 2);
    expect(controller.state.status, AuthStatus.authenticated);
  });

  test('刷新后仍未认证会清空 Keychain Token 并回到登录态', () async {
    final tokenStore = MemoryTokenStore(
      const TokenPair(accessToken: 'expired-access', refreshToken: 'refresh-1'),
    );
    final repository = FakeAuthRepository(alwaysUnauthorized: true);
    final controller = AuthController(
      repository: repository,
      tokenStore: tokenStore,
    );

    await expectLater(
      controller.loadCurrentUser(),
      throwsA(isA<AuthException>()),
    );

    expect(await tokenStore.read(), isNull);
    expect(repository.refreshCalls, 1);
    expect(controller.state.status, AuthStatus.unauthenticated);
  });
}

/// 在单元测试中模拟 Keychain，仅保存当前 Token 对。
final class MemoryTokenStore implements TokenStore {
  MemoryTokenStore(this._tokens);

  TokenPair? _tokens;

  @override
  Future<void> clear() async => _tokens = null;

  @override
  Future<TokenPair?> read() async => _tokens;

  @override
  Future<void> write(TokenPair tokens) async => _tokens = tokens;
}

/// 模拟首次受保护请求 401、刷新成功后重试成功的认证仓库。
final class FakeAuthRepository implements AuthRepository {
  FakeAuthRepository({this.alwaysUnauthorized = false});

  final bool alwaysUnauthorized;
  int currentUserCalls = 0;
  int refreshCalls = 0;

  @override
  Future<UserProfile> loadCurrentUser() async {
    currentUserCalls += 1;
    if (currentUserCalls == 1) {
      await refresh('refresh-1');
      currentUserCalls += 1;
    }
    if (alwaysUnauthorized) {
      throw const AuthException.unauthorized();
    }
    return const UserProfile(
      id: 'user-1',
      username: 'alice',
      displayName: 'Alice',
      role: 'USER',
      timezone: 'Asia/Shanghai',
    );
  }

  @override
  Future<TokenPair> refresh(String refreshToken) async {
    refreshCalls += 1;
    return const TokenPair(
      accessToken: 'new-access',
      refreshToken: 'refresh-2',
    );
  }

  @override
  Future<TokenPair> login(String username, String password) {
    throw UnimplementedError();
  }

  @override
  Future<void> logout(String refreshToken) async {}
}
