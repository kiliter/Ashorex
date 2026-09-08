import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/app/router.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/auth/presentation/connection_recovery_page.dart';
import 'package:shangan_ios/features/auth/presentation/login_page.dart';

import '../support/fake_backend.dart';
import '../support/fixtures.dart';

/// 路由重定向由认证状态单向驱动：业务页面不自行判断 Token，
/// 未登录一律回 `/login`，服务不可用回 `/connection-unavailable`。
///
/// 路由必须挂进真实 widget 树才会解析 `currentConfiguration`，
/// 因此全部用 `testWidgets` 而不是纯 `test`。
void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  testWidgets('未登录时任何位置都被重定向到登录页', (tester) async {
    final router = await _mount(tester, _Scenario.unauthenticated);

    router.go('/home');
    await tester.pumpAndSettle();

    expect(_location(router), '/login');
    expect(find.byType(LoginPage), findsOneWidget);
  });

  testWidgets('初始化中停在加载页，避免闪过登录页', (tester) async {
    final router = await _mount(tester, _Scenario.initializing);

    router.go('/home');
    await tester.pump();

    expect(_location(router), '/loading');
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });

  testWidgets('服务不可用时进入连接恢复页', (tester) async {
    final router = await _mount(tester, _Scenario.serviceUnavailable);

    router.go('/home');
    await tester.pumpAndSettle();

    expect(_location(router), '/connection-unavailable');
    expect(find.byType(ConnectionRecoveryPage), findsOneWidget);
  });

  testWidgets('已登录时根路径与登录页都跳到首页', (tester) async {
    final router = await _mount(tester, _Scenario.authenticated);

    router.go('/');
    await tester.pump();
    expect(_location(router), '/home');

    router.go('/login');
    await tester.pump();
    expect(_location(router), '/home');
    await tester.pumpAndSettle();
  });

  testWidgets('已登录时业务路由保留路径参数', (tester) async {
    final router = await _mount(tester, _Scenario.authenticated);

    router.go('/player/t-1?date=2026-09-01');
    await tester.pump();
    expect(_location(router), '/player/t-1?date=2026-09-01');

    router.go('/focus/t-2');
    await tester.pump();
    expect(_location(router), '/focus/t-2');

    router.go('/supervisor/u-2');
    await tester.pump();
    // 学员会话不能用深链接绕过身份选择。
    expect(_location(router), '/home');
    await tester.pumpAndSettle();
  });

  testWidgets('督学会话保留学员路径并拒绝跳入学习端', (tester) async {
    final router = await _mount(tester, _Scenario.supervisor);
    router.go('/supervisor/u-2');
    await tester.pumpAndSettle();
    expect(_location(router), '/supervisor/u-2');
    router.go('/player/t-1');
    await tester.pumpAndSettle();
    expect(_location(router), '/supervisor');
  });

  testWidgets('未完成汇总与目标管理是独立路由，不在 Tab 内', (tester) async {
    final router = await _mount(tester, _Scenario.authenticated);

    router.go('/todos/pending');
    await tester.pump();
    expect(_location(router), '/todos/pending');

    router.go('/goals');
    await tester.pump();
    expect(_location(router), '/goals');
    await tester.pumpAndSettle();
  });
}

enum _Scenario {
  initializing,
  unauthenticated,
  authenticated,
  supervisor,
  serviceUnavailable,
}

String _location(GoRouter router) {
  return router.routerDelegate.currentConfiguration.uri.toString();
}

/// 按场景构造真实的 [AuthController]，把路由挂进 widget 树后返回。
Future<GoRouter> _mount(WidgetTester tester, _Scenario scenario) async {
  final tokenStore = _MemoryTokenStore(
    scenario == _Scenario.unauthenticated
        ? null
        : const TokenPair(accessToken: 'access', refreshToken: 'refresh'),
  );
  final controller = AuthController(
    repository: _StubAuthRepository(scenario),
    roleStore: _ScenarioRoleStore(scenario),
    tokenStore: tokenStore,
  );
  addTearDown(controller.dispose);
  // initializing 场景刻意不调用 initialize，保留控制器的初始状态。
  if (scenario != _Scenario.initializing) {
    await controller.initialize();
  }
  final router = createRouter(controller);
  addTearDown(router.dispose);
  final serverController = ServerConfigurationController(
    initialConfiguration: ServerConfiguration.parse('https://shangan.test'),
    store: _MemoryServerConfigurationStore(),
    tokenStore: tokenStore,
  );
  addTearDown(serverController.dispose);
  // 目标页面本身要取数，这里统一挂空响应假后端，保证断言只反映重定向结果。
  final backend = FakeBackend()
    ..on('GET', '/api/v1/exam-goals', json: const [])
    ..on('GET', '/api/v1/todos', json: dayViewJson())
    ..on(
      'GET',
      '/api/v1/todos/pending-summary',
      json: {
        'total': 0,
        'countByType': <String, Object?>{},
        'items': <Object?>[],
      },
    )
    ..on('GET', '/api/v1/nags/pending')
    ..on('GET', '/api/v1/me', json: meJson())
    ..on('GET', '/api/v1/catalog/facets', json: const {})
    ..on('GET', '/api/v1/catalog/courses', json: const [])
    ..on('GET', '/api/v1/stats', json: statsJson())
    ..on('GET', '/api/v1/supervisor/learners', json: const []);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authControllerProvider.overrideWithValue(controller),
        serverConfigurationControllerProvider.overrideWithValue(
          serverController,
        ),
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
      child: MaterialApp.router(routerConfig: router),
    ),
  );
  await tester.pump();
  // 先卸载路由页面并排空退出补报，再释放路由与认证依赖。
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  });
  return router;
}

final class _MemoryServerConfigurationStore
    implements ServerConfigurationStore {
  @override
  Future<ServerConfiguration> load({required String defaultBaseUrl}) async =>
      ServerConfiguration.parse(defaultBaseUrl);

  @override
  Future<void> save(ServerConfiguration configuration) async {}
}

final class _MemoryTokenStore implements TokenStore {
  _MemoryTokenStore(this._tokens);

  TokenPair? _tokens;

  @override
  Future<void> clear() async => _tokens = null;

  @override
  Future<TokenPair?> read() async => _tokens;

  @override
  Future<void> write(TokenPair tokens) async => _tokens = tokens;
}

final class _StubAuthRepository implements AuthRepository {
  _StubAuthRepository(this.scenario);

  final _Scenario scenario;

  @override
  Future<UserProfile> loadCurrentUser() async {
    if (scenario == _Scenario.serviceUnavailable) {
      throw const ApiException(
        statusCode: 503,
        errorCode: 'SERVICE_UNAVAILABLE',
        message: '服务端暂时不可用',
      );
    }
    return UserProfile(
      id: 'u-1',
      username: 'demo',
      displayName: '张三',
      roles: [scenario == _Scenario.supervisor ? 'SUPERVISOR' : 'LEARNER'],
      timezone: 'Asia/Shanghai',
    );
  }

  @override
  Future<TokenPair> login(String username, String password) async =>
      throw UnimplementedError();

  @override
  Future<void> logout(String refreshToken) async {}

  @override
  Future<TokenPair> refresh(String refreshToken) async =>
      throw UnimplementedError();
}

/// 路由测试恢复对应会话身份，不绕过真实身份校验。
class _ScenarioRoleStore implements SessionRoleStore {
  _ScenarioRoleStore(this.scenario);
  final _Scenario scenario;
  @override
  Future<String?> readRole() async =>
      scenario == _Scenario.supervisor ? 'SUPERVISOR' : 'LEARNER';
  @override
  Future<void> writeRole(String? role) async {}
}
