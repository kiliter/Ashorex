import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import '../../core/auth/auth_controller_test.dart' show MemoryTokenStore;

/// 身份属于登录会话，恢复登录保持选择，无权限时不保留凭据。
void main() {
  test('督学登录和恢复保持身份，退出清除身份', () async {
    final tokens = MemoryTokenStore(null);
    final roles = _Roles();
    final controller = AuthController(
      repository: _Repository(),
      tokenStore: tokens,
      roleStore: roles,
    );
    await controller.login('test', 'test', role: 'SUPERVISOR');
    expect(controller.isSupervisorSession, isTrue);
    expect(roles.role, 'SUPERVISOR');
    final restored = AuthController(
      repository: _Repository(),
      tokenStore: tokens,
      roleStore: roles,
    );
    await restored.initialize();
    expect(restored.state.status, AuthStatus.authenticated);
    expect(restored.isSupervisorSession, isTrue);
    await restored.logout();
    expect(await tokens.read(), isNull);
    expect(roles.role, isNull);
  });
  test('学员账号不能选择督学身份', () async {
    final tokens = MemoryTokenStore(null);
    final controller = AuthController(
      repository: _Repository(supervisor: false),
      tokenStore: tokens,
    );
    await expectLater(
      controller.login('test', 'test', role: 'SUPERVISOR'),
      throwsA(isA<AuthException>()),
    );
    expect(controller.state.status, AuthStatus.unauthenticated);
    expect(controller.state.message, '账号不具备所选身份，请重新选择');
    expect(await tokens.read(), isNull);
  });
}

/// 内存身份存储模拟进程重建后的持久化读取。
class _Roles implements SessionRoleStore {
  String? role;
  @override
  Future<String?> readRole() async => role;
  @override
  Future<void> writeRole(String? value) async {
    role = value;
  }
}

/// 仅提供身份与认证协议，权限判断仍由真实控制器执行。
class _Repository implements AuthRepository {
  _Repository({this.supervisor = true});
  final bool supervisor;
  @override
  Future<UserProfile> loadCurrentUser() async => UserProfile(
    id: 'u',
    username: 'test',
    displayName: '测试',
    roles: ['LEARNER', if (supervisor) 'SUPERVISOR'],
    timezone: 'Asia/Shanghai',
  );
  @override
  Future<TokenPair> login(String username, String password) async =>
      const TokenPair(accessToken: 'a', refreshToken: 'r');
  @override
  Future<TokenPair> refresh(String token) => login('', '');
  @override
  Future<void> logout(String token) async {}
}
