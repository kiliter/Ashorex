import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/app/app.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';
import 'package:shangan_ios/features/focus/data/focus_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/profile/data/preferences_repository.dart';
import 'package:shangan_ios/features/quiz/data/quiz_repository.dart';
import 'package:shangan_ios/features/reporting/data/report_repository.dart';

typedef ConfiguredAppBuilder = Widget Function(
  BuildContext context,
  ServerConfigurationController controller,
);

/// App 最外层启动组件，负责读取连接配置并在服务器切换后重建全部依赖。
final class ApplicationBootstrap extends StatefulWidget {
  const ApplicationBootstrap({
    required this.defaultBaseUrl,
    this.configurationStore,
    this.tokenStore,
    this.configuredAppBuilder,
    super.key,
  });

  final String defaultBaseUrl;
  final ServerConfigurationStore? configurationStore;
  final TokenStore? tokenStore;

  /// 仅用于隔离 Widget 测试；生产环境始终构建完整的上岸依赖图。
  final ConfiguredAppBuilder? configuredAppBuilder;

  @override
  State<ApplicationBootstrap> createState() => _ApplicationBootstrapState();
}

final class _ApplicationBootstrapState extends State<ApplicationBootstrap> {
  late final Future<ServerConfigurationController> _initialization =
      _initialize();
  ServerConfigurationController? _controller;
  TokenStore? _tokenStore;

  Future<ServerConfigurationController> _initialize() async {
    final store =
        widget.configurationStore ??
        await SharedPreferencesServerConfigurationStore.create();
    final tokenStore = widget.tokenStore ?? SecureTokenStore();
    final configuration = await store.load(
      defaultBaseUrl: widget.defaultBaseUrl,
    );
    final controller = ServerConfigurationController(
      initialConfiguration: configuration,
      store: store,
      tokenStore: tokenStore,
    );
    controller.addListener(_configurationChanged);
    _controller = controller;
    _tokenStore = tokenStore;
    return controller;
  }

  void _configurationChanged() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    final controller = _controller;
    if (controller != null) {
      controller.removeListener(_configurationChanged);
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<ServerConfigurationController>(
      future: _initialization,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const _BootstrapStatusApp(message: '服务器配置加载失败，请重新启动 App');
        }
        final controller = snapshot.data;
        final tokenStore = _tokenStore;
        if (controller == null || tokenStore == null) {
          return const _BootstrapStatusApp();
        }

        final configurationKey = ValueKey(controller.configuration.baseUrl);
        final testBuilder = widget.configuredAppBuilder;
        if (testBuilder != null) {
          return KeyedSubtree(
            key: configurationKey,
            child: testBuilder(context, controller),
          );
        }
        return _ConfiguredShanganApplication(
          key: configurationKey,
          configuration: controller.configuration,
          configurationController: controller,
          tokenStore: tokenStore,
        );
      },
    );
  }
}

/// 单个服务端地址对应一套不可混用的 ApiClient、认证控制器和 Repository。
final class _ConfiguredShanganApplication extends StatefulWidget {
  const _ConfiguredShanganApplication({
    required this.configuration,
    required this.configurationController,
    required this.tokenStore,
    super.key,
  });

  final ServerConfiguration configuration;
  final ServerConfigurationController configurationController;
  final TokenStore tokenStore;

  @override
  State<_ConfiguredShanganApplication> createState() =>
      _ConfiguredShanganApplicationState();
}

final class _ConfiguredShanganApplicationState
    extends State<_ConfiguredShanganApplication> {
  late final ApiClient _apiClient;
  late final AuthController _authController;
  late final PreferencesRepository _preferencesRepository;
  late final CatalogRepository _catalogRepository;
  late final DashboardRepository _dashboardRepository;
  late final ExamRepository _examRepository;
  late final PlanRepository _planRepository;
  late final WatchRepository _watchRepository;
  late final QuizRepository _quizRepository;
  late final FocusRepository _focusRepository;
  late final ReportRepository _reportRepository;
  late final Future<void> _authenticationInitialization;

  @override
  void initState() {
    super.initState();
    _apiClient = ApiClient.create(
      baseUrl: widget.configuration.baseUrl,
      tokenStore: widget.tokenStore,
    );
    _authController = AuthController(
      repository: RemoteAuthRepository(_apiClient),
      tokenStore: widget.tokenStore,
    );
    _apiClient.onAuthenticationLost = _authController.handleAuthenticationLost;
    _preferencesRepository = RemotePreferencesRepository(_apiClient);
    _catalogRepository = RemoteCatalogRepository(_apiClient);
    _dashboardRepository = RemoteDashboardRepository(_apiClient);
    _examRepository = RemoteExamRepository(_apiClient);
    _planRepository = RemotePlanRepository(_apiClient);
    _quizRepository = RemoteQuizRepository(_apiClient);
    _focusRepository = RemoteFocusRepository(_apiClient);
    _reportRepository = RemoteReportRepository(_apiClient);
    _authenticationInitialization = _initializeAuthenticationAndPlayer();
  }

  Future<void> _initializeAuthenticationAndPlayer() async {
    _watchRepository = RemoteWatchRepository(
      api: _apiClient,
      baseUrl: widget.configuration.baseUrl,
      deviceId: await loadOrCreatePlayerDeviceId(),
    );
    await _authController.initialize();
  }

  @override
  void dispose() {
    _apiClient.close();
    _authController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<void>(
      future: _authenticationInitialization,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const _BootstrapStatusApp(message: 'App 初始化失败，请稍后重试');
        }
        if (snapshot.connectionState != ConnectionState.done) {
          return const _BootstrapStatusApp();
        }
        return ProviderScope(
          overrides: [
            authControllerProvider.overrideWithValue(_authController),
            serverConfigurationControllerProvider.overrideWithValue(
              widget.configurationController,
            ),
            catalogRepositoryProvider.overrideWithValue(_catalogRepository),
            dashboardRepositoryProvider.overrideWithValue(_dashboardRepository),
            examRepositoryProvider.overrideWithValue(_examRepository),
            planRepositoryProvider.overrideWithValue(_planRepository),
            watchRepositoryProvider.overrideWithValue(_watchRepository),
            quizRepositoryProvider.overrideWithValue(_quizRepository),
            focusRepositoryProvider.overrideWithValue(_focusRepository),
            reportRepositoryProvider.overrideWithValue(_reportRepository),
            preferencesRepositoryProvider.overrideWithValue(
              _preferencesRepository,
            ),
          ],
          child: ShanganApp(authController: _authController),
        );
      },
    );
  }
}

/// 启动阶段也使用完整 MaterialApp，确保错误和进度在 iOS 上可读。
final class _BootstrapStatusApp extends StatelessWidget {
  const _BootstrapStatusApp({this.message});

  final String? message;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: Center(
          child: message == null
              ? const CircularProgressIndicator()
              : Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text(message!, textAlign: TextAlign.center),
                ),
        ),
      ),
    );
  }
}
