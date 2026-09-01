import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/profile/data/preferences_repository.dart';
import 'package:shangan_ios/features/profile/presentation/settings_page.dart';

void main() {
  testWidgets('所有用户通过滑杆设置验活进度百分比且默认显示 50%', (tester) async {
    final auth = AuthController(
      repository: _UserAuthRepository(),
      tokenStore: _MemoryTokenStore(),
    );
    final preferences = _PreferencesRepository();
    await auth.login('admin', 'correct-password');
    addTearDown(auth.dispose);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWithValue(auth),
          preferencesRepositoryProvider.overrideWithValue(preferences),
        ],
        child: const MaterialApp(home: SettingsPage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mockExamPresetSettings')), findsNothing);
    expect(find.byKey(const Key('aliveCheckPercentSlider')), findsOneWidget);
    expect(find.text('每推进 50% 验活一次'), findsOneWidget);

    final slider = tester.widget<Slider>(
      find.byKey(const Key('aliveCheckPercentSlider')),
    );
    slider.onChanged!(25);
    await tester.pump();
    expect(find.text('每推进 25% 验活一次'), findsOneWidget);

    await tester.drag(find.byType(ListView), const Offset(0, -1000));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('savePreferences')));
    await tester.pumpAndSettle();

    expect(preferences.saved?.aliveCheckEnabled, isTrue);
    expect(preferences.saved?.aliveCheckIntervalPercent, 25);
    expect(find.text('设置已保存'), findsOneWidget);
  });
}

/// 测试仓库记录页面保存的数据，避免访问真实偏好接口。
final class _PreferencesRepository implements PreferencesRepository {
  UserPreferences? saved;

  @override
  Future<UserPreferences> load() async => const UserPreferences(
    timezone: 'Asia/Shanghai',
    aliveCheckEnabled: true,
    aliveCheckIntervalPercent: 50,
    dayEndLocalTime: '23:59',
  );

  @override
  Future<UserPreferences> update(UserPreferences preferences) async {
    saved = preferences;
    return preferences;
  }
}

/// 测试使用普通用户身份，验证百分比滑杆不再受管理员角色限制。
final class _UserAuthRepository implements AuthRepository {
  @override
  Future<TokenPair> login(String username, String password) async =>
      const TokenPair(accessToken: 'access', refreshToken: 'refresh');

  @override
  Future<UserProfile> loadCurrentUser() async => const UserProfile(
    id: 'user-1',
    username: 'learner',
    displayName: '学习者',
    role: 'USER',
    timezone: 'Asia/Shanghai',
  );

  @override
  Future<void> logout(String refreshToken) async {}

  @override
  Future<TokenPair> refresh(String refreshToken) {
    throw UnimplementedError();
  }
}

/// Widget 测试使用内存 Token，避免依赖 Keychain。
final class _MemoryTokenStore implements TokenStore {
  TokenPair? _tokens;

  @override
  Future<void> clear() async => _tokens = null;

  @override
  Future<TokenPair?> read() async => _tokens;

  @override
  Future<void> write(TokenPair tokens) async => _tokens = tokens;
}
