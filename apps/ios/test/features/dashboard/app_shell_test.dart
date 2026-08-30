import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';
import 'package:shangan_ios/features/dashboard/presentation/app_shell.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';
import 'package:shangan_ios/features/reporting/data/report_repository.dart';

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
          dashboardRepositoryProvider.overrideWithValue(_DashboardRepository()),
          reportRepositoryProvider.overrideWithValue(_ReportRepository()),
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

/// 根壳层会预创建数据 Tab，测试以固定日报避免访问真实接口。
final class _ReportRepository implements ReportRepository {
  @override
  Future<DailyReportData> loadDaily(DateTime date) async => DailyReportData(
    date: date,
    planStatus: 'LOCKED',
    plannedSeconds: 1500,
    videoStudySeconds: 0,
    focusSeconds: 0,
    completedTasks: 0,
    totalTasks: 1,
    completionRate: 0,
    videoCompletedCount: 0,
    answerCount: 0,
    correctAnswerCount: 0,
    answerAccuracy: 0,
    aliveCheckFailureCount: 0,
    abandoned: false,
    newDebtSeconds: 0,
    repaidDebtSeconds: 0,
    openDebtSeconds: 0,
    judgmentText: '今天的学习尚未完成。',
    generatedAt: DateTime.utc(2026, 8, 30),
  );

  @override
  Future<WeeklyReportData> loadWeekly(DateTime weekStart) {
    throw UnimplementedError();
  }
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
