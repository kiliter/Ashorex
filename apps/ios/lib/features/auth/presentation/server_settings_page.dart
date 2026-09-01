import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_health_checker.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';

/// 登录前可访问的服务器设置，只在健康检查通过后切换连接目标。
final class ServerSettingsPage extends ConsumerStatefulWidget {
  const ServerSettingsPage({super.key});

  @override
  ConsumerState<ServerSettingsPage> createState() => _ServerSettingsPageState();
}

final class _ServerSettingsPageState extends ConsumerState<ServerSettingsPage> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _addressController;
  bool _saving = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _addressController = TextEditingController(
      text: ref
          .read(serverConfigurationControllerProvider)
          .configuration
          .baseUrl,
    );
  }

  @override
  void dispose() {
    _addressController.dispose();
    super.dispose();
  }

  String? _validateAddress(String? value) {
    try {
      ServerConfiguration.parse(value ?? '');
      return null;
    } on FormatException catch (exception) {
      return exception.message;
    }
  }

  Future<void> _testAndSave() async {
    if (!_formKey.currentState!.validate()) return;
    final configuration = ServerConfiguration.parse(_addressController.text);
    setState(() {
      _saving = true;
      _message = null;
    });
    try {
      await ref.read(serverHealthCheckerProvider).check(configuration);
      await ref
          .read(serverConfigurationControllerProvider)
          .switchTo(configuration);
      if (!mounted) return;
      setState(() => _message = '服务器已切换，请重新登录');
      await Navigator.of(context).maybePop();
    } on ServerConnectionException catch (exception) {
      if (mounted) setState(() => _message = exception.message);
    } catch (_) {
      if (mounted) setState(() => _message = '服务器地址保存失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final current = ref
        .read(serverConfigurationControllerProvider)
        .configuration;
    return Scaffold(
      appBar: AppBar(title: const Text('服务器设置')),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
          children: [
            const ShanganNotice(
              title: '高级部署设置',
              message: '切换服务器会退出当前账号，并清除本机登录状态。',
              tone: ShanganTagTone.risk,
            ),
            const SizedBox(height: 20),
            const ShanganEyebrow('当前服务端地址'),
            const SizedBox(height: 7),
            SelectableText(
              current.baseUrl,
              style: shanganNumberStyle(context, fontSize: 13),
            ),
            const SizedBox(height: 22),
            TextFormField(
              key: const Key('serverAddressField'),
              controller: _addressController,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.done,
              autocorrect: false,
              enableSuggestions: false,
              decoration: const InputDecoration(
                labelText: '新服务端地址',
                hintText: 'http://127.0.0.1:18080',
                helperText: '填写完整的 http:// 或 https:// 地址，不要包含 /api/v1',
              ),
              validator: _validateAddress,
              onFieldSubmitted: _saving ? null : (_) => _testAndSave(),
            ),
            const SizedBox(height: 16),
            const ShanganNotice(
              title: '保存前会测试连接',
              message:
                  '模拟器可使用 127.0.0.1；物理 iPhone 需要填写 Mac 局域网地址或可访问的 HTTPS 域名。',
            ),
            if (_message != null) ...[
              const SizedBox(height: 12),
              Text(
                _message!,
                key: const Key('serverSettingsMessage'),
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: _message!.startsWith('服务器已')
                      ? Theme.of(context).colorScheme.primary
                      : Theme.of(context).colorScheme.error,
                ),
              ),
            ],
            const SizedBox(height: 24),
            FilledButton.icon(
              key: const Key('testAndSaveServerButton'),
              onPressed: _saving ? null : _testAndSave,
              icon: _saving
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.check_circle_outline),
              label: Text(_saving ? '正在测试连接…' : '测试并保存'),
            ),
          ],
        ),
      ),
    );
  }
}
