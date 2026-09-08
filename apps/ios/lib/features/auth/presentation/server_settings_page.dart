import 'package:flutter/material.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
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
  List<String> _history = [];
  bool _saving = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _loadHistory();
    _addressController = TextEditingController(
      text: ref
          .read(serverConfigurationControllerProvider)
          .configuration
          .baseUrl,
    );
  }

  /// 历史读取失败仍允许输入新地址；当前地址始终可选。
  Future<void> _loadHistory() async {
    try {
      final items = await ref.read(serverHistoryStoreProvider).read();
      if (mounted) setState(() => _history = items);
    } catch (_) {
      if (mounted) setState(() => _message = '读取服务器历史失败，可手动填写地址');
    }
  }

  /// 删除仅影响快捷历史；失败时保留条目并提示，避免误以为删除成功。
  Future<void> _deleteHistory(String origin) async {
    setState(() => _saving = true);
    try {
      await ref.read(serverHistoryStoreProvider).remove(origin);
      if (mounted) setState(() => _history.remove(origin));
    } catch (_) {
      if (mounted) setState(() => _message = '删除服务器记录失败，请重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
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
      final history = ref.read(serverHistoryStoreProvider);
      // 在切换导致根组件销毁前保存历史，同时纳入升级前使用的当前地址。
      await history.remember(
        ref.read(serverConfigurationControllerProvider).configuration,
      );
      await history.remember(configuration);
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
            const Text('已记住的服务器'),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final origin in _history)
                  InputChip(
                    label: Text(origin),
                    onDeleted: _saving ? null : () => _deleteHistory(origin),
                    deleteButtonTooltipMessage: '删除此服务器记录',
                    onPressed: _saving
                        ? null
                        : () =>
                              setState(() => _addressController.text = origin),
                  ),
              ],
            ),
            const SizedBox(height: 16),
            TextFormField(
              key: const Key('serverAddressField'),
              enabled: !_saving,
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
