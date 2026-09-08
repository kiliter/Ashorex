import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/app/router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// 上岸根应用，只提供 iOS 优先的亮色主题和受认证路由。
final class ShanganApp extends StatefulWidget {
  const ShanganApp({required this.authController, super.key});

  final AuthController authController;

  @override
  State<ShanganApp> createState() => _ShanganAppState();
}

final class _ShanganAppState extends State<ShanganApp> {
  late final GoRouter _router = createRouter(widget.authController);

  @override
  void dispose() {
    _router.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: '上岸',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.light,
      theme: ShanganTheme.light(),
      locale: const Locale('zh', 'CN'),
      supportedLocales: const [Locale('zh', 'CN')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      routerConfig: _router,
    );
  }
}
