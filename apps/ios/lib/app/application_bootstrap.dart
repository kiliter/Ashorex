import 'package:shangan_ios/core/player/progress_queue.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/app/app.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/data/shangan_repository.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/device/attachment_picker.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

typedef ConfiguredAppBuilder =
    Widget Function(
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
  late final ShanganRepository _repository;
  late final ProgressOutbox _progressOutbox;
  String? _businessSession;
  late final Future<void> _authenticationInitialization;

  @override
  void initState() {
    super.initState();
    _apiClient = ApiClient.create(
      baseUrl: widget.configuration.baseUrl,
      tokenStore: widget.tokenStore,
    );
    _authController = AuthController(
      roleStore: SecureSessionRoleStore(),
      repository: RemoteAuthRepository(_apiClient),
      tokenStore: widget.tokenStore,
    );
    _apiClient.onAuthenticationLost = _authController.handleAuthenticationLost;
    _authController.addListener(_sessionChanged);
    _repository = ShanganRepository(_apiClient);
    _progressOutbox = ProgressOutbox(
      repository: _repository,
      store: PreferencesProgressStore(),
      // 退出后不重放，重新登录同一服务器账号时恢复其原队列。
      currentScope: () {
        final state = _authController.state;
        if (state.status != AuthStatus.authenticated) return null;
        return '${widget.configuration.baseUrl}|${state.user!.id}';
      },
    );
    _authenticationInitialization = _initializeAuthenticationAndPlayer();
  }

  /// 业务缓存随身份重建，避免同一服务器换账号后看到上一人的 Todo/设置。
  void _sessionChanged() {
    final state = _authController.state;
    final session = state.status == AuthStatus.authenticated
        ? '${state.user!.id}|${_authController.isSupervisorSession}'
        : null;
    if (session == _businessSession || !mounted) return;
    setState(() => _businessSession = session);
  }

  Future<void> _initializeAuthenticationAndPlayer() async {
    await _authController.initialize();
  }

  @override
  void dispose() {
    _authController.removeListener(_sessionChanged);
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
          key: ValueKey(_businessSession),
          overrides: [
            authControllerProvider.overrideWithValue(_authController),
            serverConfigurationControllerProvider.overrideWithValue(
              widget.configurationController,
            ),
            shanganRepositoryProvider.overrideWithValue(_repository),
            progressOutboxProvider.overrideWithValue(_progressOutbox),
            screenWakeLockProvider.overrideWithValue(
              const WakelockPlusScreenWakeLock(),
            ),
            // 附件选择器只有真机 / 模拟器可用，Widget 测试用假实现覆盖。
            attachmentPickerProvider.overrideWithValue(
              const PlatformAttachmentPicker(),
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
