import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/features/auth/presentation/login_page.dart';
import 'package:shangan_ios/features/dashboard/presentation/app_shell.dart';
import 'package:shangan_ios/features/profile/presentation/settings_page.dart';

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
        path: '/settings',
        builder: (context, state) => const SettingsPage(),
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
