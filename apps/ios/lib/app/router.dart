import 'package:shangan_ios/core/presence/app_activity.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/features/auth/presentation/connection_recovery_page.dart';
import 'package:shangan_ios/features/auth/presentation/login_page.dart';
import 'package:shangan_ios/features/catalog/presentation/library_page.dart';
import 'package:shangan_ios/features/focus/presentation/focus_run_page.dart';
import 'package:shangan_ios/features/home/presentation/pending_summary_page.dart';
import 'package:shangan_ios/features/player/presentation/player_page.dart';
import 'package:shangan_ios/features/profile/presentation/goals_page.dart';
import 'package:shangan_ios/features/shell/presentation/app_shell.dart';
import 'package:shangan_ios/features/supervisor/presentation/supervisor_shell.dart';

export 'package:shangan_ios/features/shell/presentation/app_shell.dart'
    show shanganRouteObserver;

/// 创建受认证状态驱动的根路由，业务页面不自行判断 Token。
GoRouter createRouter(AuthController authController) {
  return GoRouter(
    initialLocation: '/',
    observers: [shanganRouteObserver, activityRouteObserver],
    refreshListenable: authController,
    redirect: (context, state) {
      final status = authController.state.status;
      final onLogin = state.matchedLocation == '/login';
      final onLoading = state.matchedLocation == '/loading';
      final onConnectionError =
          state.matchedLocation == '/connection-unavailable';
      if (status == AuthStatus.initializing ||
          status == AuthStatus.authenticating) {
        return onLoading ? null : '/loading';
      }
      if (status == AuthStatus.unauthenticated) {
        return onLogin ? null : '/login';
      }
      if (status == AuthStatus.serviceUnavailable) {
        return onConnectionError ? null : '/connection-unavailable';
      }
      // 会话身份同时约束深链接，不能绕过登录选择进入另一端。
      final supervisor = authController.isSupervisorSession;
      if (state.uri.scheme == 'shangan' && state.uri.host == 'home') {
        return supervisor ? '/supervisor' : '/home';
      }
      final inSupervisor = state.matchedLocation.startsWith('/supervisor');
      if (onLogin ||
          onLoading ||
          onConnectionError ||
          state.matchedLocation == '/' ||
          supervisor != inSupervisor) {
        return supervisor ? '/supervisor' : '/home';
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
      GoRoute(
        path: '/connection-unavailable',
        builder: (context, state) => const ConnectionRecoveryPage(),
      ),
      GoRoute(path: '/home', builder: (context, state) => const AppShell()),
      GoRoute(
        path: '/todos/pending',
        builder: (context, state) => const AppActivityScope(
          activity: AppActivity('PENDING', 'BROWSING'),
          child: PendingSummaryPage(),
        ),
      ),
      GoRoute(
        path: '/goals',
        builder: (context, state) => const AppActivityScope(
          activity: AppActivity('GOALS', 'BROWSING'),
          child: GoalsPage(),
        ),
      ),
      GoRoute(
        path: '/player/:todoId',
        builder: (context, state) => PlayerPage(
          todoId: state.pathParameters['todoId']!,
          localDate: DateTime.tryParse(state.uri.queryParameters['date'] ?? ''),
        ),
      ),
      GoRoute(
        path: '/focus/:todoId',
        builder: (context, state) => FocusRunPage(
          todoId: state.pathParameters['todoId']!,
          localDate: DateTime.tryParse(state.uri.queryParameters['date'] ?? ''),
        ),
      ),
      GoRoute(
        path: '/courses/:courseId',
        builder: (context, state) => AppActivityScope(
          activity: const AppActivity('COURSE', 'BROWSING'),
          child: CourseDetailPage(courseId: state.pathParameters['courseId']!),
        ),
      ),
      GoRoute(
        path: '/supervisor',
        builder: (context, state) => const SupervisorShell(),
      ),
      GoRoute(
        path: '/supervisor/:learnerId',
        builder: (context, state) => SupervisorLearnerPage(
          learnerId: state.pathParameters['learnerId']!,
        ),
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
