import 'package:flutter/material.dart';
import 'package:shangan_ios/core/storage/remembered_login_store.dart';
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
  String _role = 'LEARNER';
  bool _remember = false;
  bool _storageBusy = true;
  String? _storageError;
  late final String _origin;
  late final RememberedLoginStore _rememberedStore;

  @override
  void initState() {
    super.initState();
    _origin = ref
        .read(serverConfigurationControllerProvider)
        .configuration
        .baseUrl;
    _rememberedStore = ref.read(rememberedLoginStoreProvider);
    _restoreCredentials();
  }

  /// 恢复期间禁用输入，避免异步读取覆盖用户刚输入的内容。
  Future<void> _restoreCredentials() async {
    try {
      final saved = await _rememberedStore.read(_origin);
      if (!mounted) return;
      if (saved != null) {
        _usernameController.text = saved.username;
        _passwordController.text = saved.password;
        _remember = true;
      }
    } catch (_) {
      _storageError = '无法读取已记住的账号，请手动输入';
    } finally {
      if (mounted) setState(() => _storageBusy = false);
    }
  }

  /// 取消勾选立即清除；删除失败时保留勾选并明确提示，不假装已删除。
  Future<void> _changeRemember(bool value) async {
    if (value) {
      setState(() => _remember = true);
      return;
    }
    setState(() => _storageBusy = true);
    try {
      await _rememberedStore.write(_origin, null);
      if (mounted) {
        setState(() {
          _remember = false;
          _storageError = null;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _storageError = '清除失败，请重试取消记住');
    } finally {
      if (mounted) setState(() => _storageBusy = false);
    }
  }

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
    final auth = ref.read(authControllerProvider);
    final username = _usernameController.text.trim();
    final password = _passwordController.text;
    setState(() {
      _storageBusy = true;
      _storageError = null;
    });
    try {
      // 先保存用户明确选择记住的输入，避免路由跳转销毁页面后才写入。
      if (_remember) {
        await _rememberedStore.write(
          _origin,
          RememberedLogin(username, password),
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _storageBusy = false;
          _storageError = '保存失败，请重试或取消记住';
        });
      }
      return;
    }
    if (!mounted) return;
    try {
      await auth.login(username, password, role: _role);
    } catch (_) {
      // 登录错误由认证控制器提供，绝不展示或记录密码。
    }
    _storageBusy = false;
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
    final submitting =
        _storageBusy || state.status == AuthStatus.authenticating;
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
                        // 身份仅在提交登录前选择，登录后固定到会话结束。
                        SegmentedButton<String>(
                          segments: const [
                            ButtonSegment(value: 'LEARNER', label: Text('学员端')),
                            ButtonSegment(
                              value: 'SUPERVISOR',
                              label: Text('督学端'),
                            ),
                          ],
                          selected: {_role},
                          onSelectionChanged: submitting
                              ? null
                              : (values) =>
                                    setState(() => _role = values.single),
                        ),
                        const SizedBox(height: 18),
                        TextFormField(
                          key: const Key('usernameField'),
                          enabled: !submitting,
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
                          enabled: !submitting,
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
                        Material(
                          color: Colors.transparent,
                          child: CheckboxListTile(
                            key: const Key('rememberLogin'),
                            contentPadding: EdgeInsets.zero,
                            controlAffinity: ListTileControlAffinity.leading,
                            title: const Text('记住用户名和密码'),
                            value: _remember,
                            onChanged: submitting
                                ? null
                                : (value) => _changeRemember(value ?? false),
                          ),
                        ),
                        if (_storageError != null)
                          Text(
                            _storageError!,
                            style: const TextStyle(color: ShanganColors.red),
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
                              : Text(_role == 'SUPERVISOR' ? '登录督学端' : '登录学员端'),
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
