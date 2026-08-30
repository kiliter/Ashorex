import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/config/server_health_checker.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/auth/presentation/login_page.dart';
import 'package:shangan_ios/features/auth/presentation/server_settings_page.dart';

void main() {
  testWidgets('登录页展示当前服务器并可进入服务器设置', (tester) async {
    final dependencies = await _dependencies();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWithValue(dependencies.auth),
          serverConfigurationControllerProvider.overrideWithValue(
            dependencies.configuration,
          ),
          serverHealthCheckerProvider.overrideWithValue(dependencies.health),
        ],
        child: const MaterialApp(home: LoginPage()),
      ),
    );

    expect(find.text('当前服务器：127.0.0.1:8080'), findsOneWidget);
    await tester.tap(find.byKey(const Key('serverSettingsButton')));
    await tester.pumpAndSettle();
    expect(find.byType(ServerSettingsPage), findsOneWidget);
  });

  testWidgets('健康检查失败时不覆盖原地址', (tester) async {
    final dependencies = await _dependencies();
    dependencies.health.error = const ServerConnectionException(
      '无法连接服务器，请检查地址和网络',
    );
    await _pumpSettings(tester, dependencies);

    await tester.enterText(
      find.byKey(const Key('serverAddressField')),
      'https://unreachable.example.com',
    );
    await tester.tap(find.byKey(const Key('testAndSaveServerButton')));
    await tester.pumpAndSettle();

    expect(find.text('无法连接服务器，请检查地址和网络'), findsOneWidget);
    expect(
      dependencies.configuration.configuration.baseUrl,
      'http://127.0.0.1:8080',
    );
    expect(dependencies.store.saveCalls, 0);
  });

  testWidgets('健康检查通过后保存新地址并清除旧 Token', (tester) async {
    final dependencies = await _dependencies();
    await _pumpSettings(tester, dependencies);

    await tester.enterText(
      find.byKey(const Key('serverAddressField')),
      'https://new.example.com/',
    );
    await tester.tap(find.byKey(const Key('testAndSaveServerButton')));
    await tester.pumpAndSettle();

    expect(dependencies.health.checked?.baseUrl, 'https://new.example.com');
    expect(
      dependencies.configuration.configuration.baseUrl,
      'https://new.example.com',
    );
    expect(dependencies.tokens.clearCalls, 1);
  });
}

Future<void> _pumpSettings(
  WidgetTester tester,
  _TestDependencies dependencies,
) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        serverConfigurationControllerProvider.overrideWithValue(
          dependencies.configuration,
        ),
        serverHealthCheckerProvider.overrideWithValue(dependencies.health),
      ],
      child: const MaterialApp(home: ServerSettingsPage()),
    ),
  );
}

Future<_TestDependencies> _dependencies() async {
  final store = _MemoryServerConfigurationStore(
    ServerConfiguration.parse('http://127.0.0.1:8080'),
  );
  final tokens = _MemoryTokenStore()
    ..tokens = const TokenPair(
      accessToken: 'old-access',
      refreshToken: 'old-refresh',
    );
  final configuration = ServerConfigurationController(
    initialConfiguration: store.current,
    store: store,
    tokenStore: tokens,
  );
  final auth = AuthController(
    repository: _FakeAuthRepository(),
    tokenStore: tokens,
  );
  await auth.initialize();
  // 认证初始化会清理测试中的失效 Token；随后重新模拟切换前的有效登录凭据。
  tokens
    ..tokens = const TokenPair(
      accessToken: 'old-access',
      refreshToken: 'old-refresh',
    )
    ..clearCalls = 0;
  return _TestDependencies(
    store: store,
    tokens: tokens,
    configuration: configuration,
    health: _FakeServerHealthChecker(),
    auth: auth,
  );
}

final class _TestDependencies {
  const _TestDependencies({
    required this.store,
    required this.tokens,
    required this.configuration,
    required this.health,
    required this.auth,
  });

  final _MemoryServerConfigurationStore store;
  final _MemoryTokenStore tokens;
  final ServerConfigurationController configuration;
  final _FakeServerHealthChecker health;
  final AuthController auth;
}

final class _MemoryServerConfigurationStore
    implements ServerConfigurationStore {
  _MemoryServerConfigurationStore(this.current);

  ServerConfiguration current;
  int saveCalls = 0;

  @override
  Future<ServerConfiguration> load({required String defaultBaseUrl}) async =>
      current;

  @override
  Future<void> save(ServerConfiguration configuration) async {
    saveCalls += 1;
    current = configuration;
  }
}

final class _MemoryTokenStore implements TokenStore {
  TokenPair? tokens;
  int clearCalls = 0;

  @override
  Future<void> clear() async {
    clearCalls += 1;
    tokens = null;
  }

  @override
  Future<TokenPair?> read() async => tokens;

  @override
  Future<void> write(TokenPair tokens) async => this.tokens = tokens;
}

final class _FakeServerHealthChecker implements ServerHealthChecker {
  ServerConfiguration? checked;
  ServerConnectionException? error;

  @override
  Future<void> check(ServerConfiguration configuration) async {
    checked = configuration;
    final failure = error;
    if (failure != null) throw failure;
  }
}

final class _FakeAuthRepository implements AuthRepository {
  @override
  Future<UserProfile> loadCurrentUser() async =>
      throw const AuthException.unauthorized();

  @override
  Future<TokenPair> login(String username, String password) async =>
      const TokenPair(accessToken: 'access', refreshToken: 'refresh');

  @override
  Future<void> logout(String refreshToken) async {}

  @override
  Future<TokenPair> refresh(String refreshToken) async =>
      const TokenPair(accessToken: 'access', refreshToken: 'refresh');
}
