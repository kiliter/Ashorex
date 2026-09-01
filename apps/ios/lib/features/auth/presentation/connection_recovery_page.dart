import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/auth/presentation/server_settings_page.dart';

/// 已登录但服务不可用时的恢复页；不会把连接故障误判成退出登录。
final class ConnectionRecoveryPage extends ConsumerWidget {
  const ConnectionRecoveryPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authControllerProvider);
    final server = ref
        .watch(serverConfigurationControllerProvider)
        .configuration;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 460),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Icon(
                    Icons.cloud_off_outlined,
                    size: 56,
                    color: ShanganColors.ochre,
                  ),
                  const SizedBox(height: 18),
                  Text(
                    '服务端暂时连不上',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    'App 不会一直转圈，也不会因为断网清除登录状态。服务恢复后可直接重新连接。',
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 22),
                  ShanganNotice(
                    title: '当前服务器',
                    message: server.baseUrl,
                    tone: ShanganTagTone.warning,
                  ),
                  const SizedBox(height: 22),
                  FilledButton.icon(
                    key: const Key('retryServerConnection'),
                    onPressed: auth.state.status == AuthStatus.initializing
                        ? null
                        : auth.retryConnection,
                    icon: const Icon(Icons.refresh),
                    label: const Text('重新连接'),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    key: const Key('changeUnavailableServer'),
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => const ServerSettingsPage(),
                      ),
                    ),
                    icon: const Icon(Icons.dns_outlined),
                    label: const Text('修改服务器'),
                  ),
                  const SizedBox(height: 10),
                  TextButton(
                    key: const Key('logoutUnavailableSession'),
                    onPressed: auth.handleAuthenticationLost,
                    child: const Text('退出登录'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
