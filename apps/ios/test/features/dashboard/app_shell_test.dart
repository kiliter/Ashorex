import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/dashboard/presentation/app_shell.dart';

void main() {
  testWidgets('登录后根壳层渲染五个固定 Tab', (tester) async {
    final controller = AuthController(
      repository: _AuthenticatedRepository(),
      tokenStore: _MemoryTokenStore(),
    );
    await controller.login('alice', 'correct-password');
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWithValue(controller),
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
        ],
        child: const MaterialApp(home: AppShell()),
      ),
    );

    expect(find.byType(NavigationDestination), findsNWidgets(5));
    for (final label in ['首页', '学习', 'AI', '数据', '我的']) {
      expect(find.text(label), findsWidgets);
    }
  });
}

final class _CatalogRepository implements CatalogRepository {
  @override
  Future<List<CourseSummary>> listCourses() async => const [];

  @override
  Future<CourseDetail> loadCourse(String courseId) {
    throw UnimplementedError();
  }
}

/// Widget 测试使用内存 Token，避免访问真机 Keychain。
final class _MemoryTokenStore implements TokenStore {
  TokenPair? _tokens;

  @override
  Future<void> clear() async => _tokens = null;

  @override
  Future<TokenPair?> read() async => _tokens;

  @override
  Future<void> write(TokenPair tokens) async => _tokens = tokens;
}

final class _AuthenticatedRepository implements AuthRepository {
  @override
  Future<TokenPair> login(String username, String password) async {
    return const TokenPair(accessToken: 'access', refreshToken: 'refresh');
  }

  @override
  Future<UserProfile> loadCurrentUser() async => const UserProfile(
    id: 'user-1',
    username: 'alice',
    displayName: 'Alice',
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
