import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/app/app.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/ai_chat/data/ai_chat_repository.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';
import 'package:shangan_ios/features/focus/data/focus_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/profile/data/preferences_repository.dart';
import 'package:shangan_ios/features/quiz/data/quiz_repository.dart';
import 'package:shangan_ios/features/reporting/data/report_repository.dart';

/// 初始化生产依赖；API 地址由构建参数提供，默认值仅用于本机开发。
Future<void> bootstrap() async {
  WidgetsFlutterBinding.ensureInitialized();
  const baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://127.0.0.1:8080',
  );
  final tokenStore = SecureTokenStore();
  final apiClient = ApiClient.create(baseUrl: baseUrl, tokenStore: tokenStore);
  final authController = AuthController(
    repository: RemoteAuthRepository(apiClient),
    tokenStore: tokenStore,
  );
  apiClient.onAuthenticationLost = authController.handleAuthenticationLost;
  final preferencesRepository = RemotePreferencesRepository(apiClient);
  final catalogRepository = RemoteCatalogRepository(apiClient);
  final dashboardRepository = RemoteDashboardRepository(apiClient);
  final examRepository = RemoteExamRepository(apiClient);
  final planRepository = RemotePlanRepository(apiClient);
  final watchRepository = RemoteWatchRepository(
    api: apiClient,
    baseUrl: baseUrl,
    deviceId: await loadOrCreatePlayerDeviceId(),
  );
  final quizRepository = RemoteQuizRepository(apiClient);
  final focusRepository = RemoteFocusRepository(apiClient);
  final reportRepository = RemoteReportRepository(apiClient);
  final aiChatRepository = RemoteAiChatRepository(apiClient);

  await authController.initialize();
  runApp(
    ProviderScope(
      overrides: [
        authControllerProvider.overrideWithValue(authController),
        catalogRepositoryProvider.overrideWithValue(catalogRepository),
        dashboardRepositoryProvider.overrideWithValue(dashboardRepository),
        examRepositoryProvider.overrideWithValue(examRepository),
        planRepositoryProvider.overrideWithValue(planRepository),
        watchRepositoryProvider.overrideWithValue(watchRepository),
        quizRepositoryProvider.overrideWithValue(quizRepository),
        focusRepositoryProvider.overrideWithValue(focusRepository),
        reportRepositoryProvider.overrideWithValue(reportRepository),
        preferencesRepositoryProvider.overrideWithValue(preferencesRepository),
        aiChatRepositoryProvider.overrideWithValue(aiChatRepository),
      ],
      child: ShanganApp(authController: authController),
    ),
  );
}
