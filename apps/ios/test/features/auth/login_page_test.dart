import 'package:shangan_ios/core/storage/remembered_login_store.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/auth/presentation/login_page.dart';

void main() {
  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
  });
  testWidgets('输入正确凭据后进入已认证状态', (tester) async {
    final tokenStore = _MemoryTokenStore();
    final controller = AuthController(
      repository: _SuccessfulAuthRepository(),
      tokenStore: tokenStore,
    );
    await controller.initialize();
    addTearDown(controller.dispose);
    final serverController = ServerConfigurationController(
      initialConfiguration: ServerConfiguration.parse('http://127.0.0.1:18080'),
      store: _MemoryServerConfigurationStore(),
      tokenStore: tokenStore,
    );
    addTearDown(serverController.dispose);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWithValue(controller),
          serverConfigurationControllerProvider.overrideWithValue(
            serverController,
          ),
        ],
        child: const MaterialApp(home: LoginPage()),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('今日学习凭证'), findsOneWidget);
    expect(find.text('准入状态'), findsOneWidget);
    expect(find.text('登录学员端'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('usernameField')), 'alice');
    await tester.enterText(
      find.byKey(const Key('passwordField')),
      'correct-password',
    );
    await tester.tap(find.byKey(const Key('rememberLogin')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('loginButton')));
    await tester.tap(find.byKey(const Key('loginButton')));
    await tester.pumpAndSettle();

    final saved = await RememberedLoginStore().read('http://127.0.0.1:18080');
    expect(saved?.username, 'alice');
    expect(saved?.password, 'correct-password');
    expect(controller.state.status, AuthStatus.authenticated);
    expect(controller.state.user?.id, 'user-1');
    expect((await tokenStore.read())?.refreshToken, 'refresh-token');
    await controller.logout();
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWithValue(controller),
          serverConfigurationControllerProvider.overrideWithValue(
            serverController,
          ),
        ],
        child: const MaterialApp(home: LoginPage()),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<TextFormField>(find.byKey(const Key('usernameField')))
          .controller!
          .text,
      'alice',
    );
    expect(
      tester
          .widget<TextFormField>(find.byKey(const Key('passwordField')))
          .controller!
          .text,
      'correct-password',
    );
    expect(
      tester
          .widget<CheckboxListTile>(find.byKey(const Key('rememberLogin')))
          .value,
      isTrue,
    );
    await tester.ensureVisible(find.byKey(const Key('rememberLogin')));
    await tester.tap(find.byKey(const Key('rememberLogin')));
    await tester.pumpAndSettle();
    expect(await RememberedLoginStore().read('http://127.0.0.1:18080'), isNull);
  });
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
  TokenPair? _tokens;

  @override
  Future<void> clear() async => _tokens = null;

  @override
  Future<TokenPair?> read() async => _tokens;

  @override
  Future<void> write(TokenPair tokens) async => _tokens = tokens;
}

final class _SuccessfulAuthRepository implements AuthRepository {
  @override
  Future<TokenPair> login(String username, String password) async {
    expect(username, 'alice');
    expect(password, 'correct-password');
    return const TokenPair(
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    );
  }

  @override
  Future<UserProfile> loadCurrentUser() async => const UserProfile(
    id: 'user-1',
    username: 'alice',
    displayName: 'Alice',
    roles: ['LEARNER'],
    timezone: 'Asia/Shanghai',
  );

  @override
  Future<void> logout(String refreshToken) async {}

  @override
  Future<TokenPair> refresh(String refreshToken) {
    throw UnimplementedError();
  }
}
