import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:shangan_ios/app/app.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';
import 'package:shangan_ios/features/profile/data/preferences_repository.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('已认证用户可以看到五 Tab 根壳层', (tester) async {
    final tokenStore = _MemoryTokenStore(
      const TokenPair(accessToken: 'access', refreshToken: 'refresh'),
    );
    final controller = AuthController(
      repository: _AuthenticatedRepository(),
      tokenStore: tokenStore,
    );
    await controller.initialize();
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWithValue(controller),
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
          dashboardRepositoryProvider.overrideWithValue(_DashboardRepository()),
          preferencesRepositoryProvider.overrideWithValue(
            _MemoryPreferencesRepository(),
          ),
        ],
        child: ShanganApp(authController: controller),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(NavigationDestination), findsNWidgets(5));
    for (final label in ['首页', '学习', 'AI', '数据', '我的']) {
      expect(find.text(label), findsWidgets);
    }
  });
}

final class _DashboardRepository implements DashboardRepository {
  @override
  Future<DashboardData> load() async => DashboardData(
    exam: ExamGoal(
      id: 'goal-1',
      name: '国考',
      examDate: DateTime(2026, 11),
      targetCompletionDate: DateTime(2026, 10, 18),
      reviewBufferDays: 14,
      timezone: 'Asia/Shanghai',
      courseIds: const ['course-1'],
    ),
    progressPressure: ProgressPressure(
      daysUntilExam: 63,
      daysUntilTarget: 49,
      totalLessons: 1,
      completedLessons: 0,
      remainingLessons: 1,
      requiredDailyPace: 0.02,
      actualDailyPace: 0,
      projectedFinishDate: null,
      riskStatus: 'AT_RISK',
    ),
    todayPlanStatus: 'NONE',
    openDebtSeconds: 0,
    studyTodaySeconds: 0,
    answerAccuracy: 0,
  );
}

final class _CatalogRepository implements CatalogRepository {
  @override
  Future<List<CourseSummary>> listCourses() async => const [];

  @override
  Future<CourseDetail> loadCourse(String courseId) {
    throw UnimplementedError();
  }
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

final class _AuthenticatedRepository implements AuthRepository {
  @override
  Future<UserProfile> loadCurrentUser() async => const UserProfile(
    id: 'user-1',
    username: 'alice',
    displayName: 'Alice',
    role: 'USER',
    timezone: 'Asia/Shanghai',
  );

  @override
  Future<TokenPair> login(String username, String password) {
    throw UnimplementedError();
  }

  @override
  Future<void> logout(String refreshToken) async {}

  @override
  Future<TokenPair> refresh(String refreshToken) {
    throw UnimplementedError();
  }
}

final class _MemoryPreferencesRepository implements PreferencesRepository {
  UserPreferences _preferences = const UserPreferences(
    timezone: 'Asia/Shanghai',
    aliveCheckLevel: 'NORMAL',
    dayEndLocalTime: '23:59',
  );

  @override
  Future<UserPreferences> load() async => _preferences;

  @override
  Future<UserPreferences> update(UserPreferences preferences) async {
    _preferences = preferences;
    return preferences;
  }
}
