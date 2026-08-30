import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/features/auth/presentation/login_page.dart';
import 'package:shangan_ios/features/catalog/presentation/course_detail_page.dart';
import 'package:shangan_ios/features/dashboard/presentation/app_shell.dart';
import 'package:shangan_ios/features/debt/presentation/debt_page.dart';
import 'package:shangan_ios/features/exam/presentation/exam_goal_page.dart';
import 'package:shangan_ios/features/focus/presentation/focus_timer_page.dart';
import 'package:shangan_ios/features/planning/presentation/plan_page.dart';
import 'package:shangan_ios/features/player/presentation/learning_player_page.dart';
import 'package:shangan_ios/features/profile/presentation/settings_page.dart';
import 'package:shangan_ios/features/quiz/presentation/quiz_page.dart';
import 'package:shangan_ios/features/reporting/presentation/daily_report_page.dart';
import 'package:shangan_ios/features/reporting/presentation/weekly_report_page.dart';

/// 创建受认证状态驱动的根路由，业务页面不自行判断 Token。
GoRouter createRouter(AuthController authController) {
  return GoRouter(
    initialLocation: '/',
    refreshListenable: authController,
    redirect: (context, state) {
      final status = authController.state.status;
      final onLogin = state.matchedLocation == '/login';
      final onLoading = state.matchedLocation == '/loading';
      if (status == AuthStatus.initializing ||
          status == AuthStatus.authenticating) {
        return onLoading ? null : '/loading';
      }
      if (status == AuthStatus.unauthenticated) {
        return onLogin ? null : '/login';
      }
      if (onLogin || onLoading || state.matchedLocation == '/') {
        return '/home';
      }
      return null;
    },
    routes: [
      GoRoute(path: '/', builder: (context, state) => const _LoadingPage()),
      GoRoute(
        path: '/loading',
        builder: (context, state) => const _LoadingPage(),
      ),
      GoRoute(path: '/login', builder: (context, state) => const LoginPage()),
      GoRoute(path: '/home', builder: (context, state) => const AppShell()),
      GoRoute(
        path: '/exam-goal',
        builder: (context, state) => const ExamGoalPage(),
      ),
      GoRoute(path: '/plan', builder: (context, state) => const PlanPage()),
      GoRoute(path: '/debts', builder: (context, state) => const DebtPage()),
      GoRoute(
        path: '/focus',
        builder: (context, state) => FocusTimerPage(
          planItemId: state.uri.queryParameters['planItemId'],
          mediaItemId: state.uri.queryParameters['mediaItemId'],
          title: state.uri.queryParameters['title'] ?? '专注学习',
          plannedSeconds:
              int.tryParse(state.uri.queryParameters['plannedSeconds'] ?? '') ??
              25 * 60,
        ),
      ),
      GoRoute(
        path: '/player/:lessonId',
        builder: (context, state) => LearningPlayerPage(
          lessonId: state.pathParameters['lessonId']!,
          planItemId: state.uri.queryParameters['planItemId'],
          title: state.uri.queryParameters['title'] ?? '视频学习',
        ),
      ),
      GoRoute(
        path: '/courses/:courseId',
        builder: (context, state) =>
            CourseDetailPage(courseId: state.pathParameters['courseId']!),
      ),
      GoRoute(
        path: '/quiz/:lessonId',
        builder: (context, state) => QuizPage(
          lessonId: state.pathParameters['lessonId']!,
          planItemId: state.uri.queryParameters['planItemId'],
        ),
      ),
      GoRoute(
        path: '/settings',
        builder: (context, state) => const SettingsPage(),
      ),
      GoRoute(
        path: '/reports/daily',
        builder: (context, state) => DailyReportPage(
          initialDate: DateTime.tryParse(
            state.uri.queryParameters['date'] ?? '',
          ),
          showAppBar: true,
        ),
      ),
      GoRoute(
        path: '/reports/weekly',
        builder: (context, state) {
          final now = DateTime.now();
          final defaultMonday = now.subtract(Duration(days: now.weekday - 1));
          return WeeklyReportPage(
            initialWeekStart:
                DateTime.tryParse(
                  state.uri.queryParameters['weekStart'] ?? '',
                ) ??
                defaultMonday,
          );
        },
      ),
    ],
  );
}

final class _LoadingPage extends StatelessWidget {
  const _LoadingPage();

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: CircularProgressIndicator()));
  }
}
