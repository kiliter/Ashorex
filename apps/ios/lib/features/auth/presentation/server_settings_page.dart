import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_health_checker.dart';

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
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              '当前服务器：${current.baseUrl}',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 20),
            TextFormField(
              key: const Key('serverAddressField'),
              controller: _addressController,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.done,
              autocorrect: false,
              enableSuggestions: false,
              decoration: const InputDecoration(
                labelText: '服务端地址',
                hintText: 'http://127.0.0.1:8080',
                helperText: '填写完整的 http:// 或 https:// 地址，不要包含 /api/v1',
                prefixIcon: Icon(Icons.dns_outlined),
              ),
              validator: _validateAddress,
              onFieldSubmitted: _saving ? null : (_) => _testAndSave(),
            ),
            const SizedBox(height: 16),
            const Card(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'iOS 模拟器可使用 127.0.0.1；物理 iPhone 需要填写 Mac 的局域网地址或可访问的 HTTPS 域名。切换服务器会退出当前账号。',
                ),
              ),
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
            SizedBox(
              height: 52,
              child: FilledButton.icon(
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
            ),
          ],
        ),
      ),
    );
  }
}
