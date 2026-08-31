import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/auth/presentation/server_settings_page.dart';

/// App 登录页，只收集凭据并委托 AuthController 调用服务端。
final class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

final class _LoginPageState extends ConsumerState<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();

  @override
  void dispose() {
    _usernameController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) {
      return;
    }
    final login = ref
        .read(authControllerProvider)
        .login(_usernameController.text, _passwordController.text);
    setState(() {});
    try {
      await login;
    } catch (_) {
      // 控制器已将可展示错误写入状态，页面不泄露底层异常。
    }
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(authControllerProvider);
    final server = ref
        .watch(serverConfigurationControllerProvider)
        .configuration;
    final state = controller.state;
    final submitting = state.status == AuthStatus.authenticating;
    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight:
                  MediaQuery.sizeOf(context).height -
                  MediaQuery.paddingOf(context).vertical -
                  44,
            ),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Align(
                    alignment: Alignment.centerRight,
                    child: IconButton.outlined(
                      key: const Key('serverSettingsButton'),
                      tooltip: '服务器设置',
                      onPressed: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (context) => const ServerSettingsPage(),
                          ),
                        );
                      },
                      icon: const Icon(
                        Icons.more_horiz,
                        color: ShanganColors.blue,
                      ),
                    ),
                  ),
                  const SizedBox(height: 28),
                  const ShanganEyebrow('今日学习凭证'),
                  const SizedBox(height: 7),
                  Text('上岸', style: Theme.of(context).textTheme.displaySmall),
                  const SizedBox(height: 7),
                  Text(
                    '今天的计划，要算数。',
                    style: Theme.of(context).textTheme.bodyLarge,
                  ),
                  const SizedBox(height: 28),
                  ShanganSurface(
                    borderColor: ShanganColors.ink,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Row(
                          children: [
                            Expanded(child: Text('准入状态')),
                            ShanganStatusTag(
                              '服务可用',
                              tone: ShanganTagTone.success,
                            ),
                          ],
                        ),
                        const SizedBox(height: 18),
                        const Divider(),
                        const SizedBox(height: 18),
                        TextFormField(
                          key: const Key('usernameField'),
                          controller: _usernameController,
                          autofillHints: const [AutofillHints.username],
                          textInputAction: TextInputAction.next,
                          decoration: const InputDecoration(labelText: '用户名'),
                          validator: (value) =>
                              value == null || value.trim().isEmpty
                              ? '请输入用户名'
                              : null,
                        ),
                        const SizedBox(height: 14),
                        TextFormField(
                          key: const Key('passwordField'),
                          controller: _passwordController,
                          obscureText: true,
                          autofillHints: const [AutofillHints.password],
                          textInputAction: TextInputAction.done,
                          onFieldSubmitted: submitting
                              ? null
                              : (_) => _submit(),
                          decoration: const InputDecoration(labelText: '密码'),
                          validator: (value) =>
                              value == null || value.isEmpty ? '请输入密码' : null,
                        ),
                        if (state.message != null) ...[
                          const SizedBox(height: 12),
                          Text(
                            state.message!,
                            key: const Key('loginError'),
                            style: const TextStyle(color: ShanganColors.red),
                          ),
                        ],
                        const SizedBox(height: 20),
                        FilledButton(
                          key: const Key('loginButton'),
                          onPressed: submitting ? null : _submit,
                          child: submitting
                              ? const SizedBox.square(
                                  dimension: 22,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Text('登录并领取今日计划'),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    '当前服务器：${server.displayLabel}',
                    key: const Key('currentServerLabel'),
                    style: shanganNumberStyle(
                      context,
                      fontSize: 12,
                    ).copyWith(color: ShanganColors.mutedInk),
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
