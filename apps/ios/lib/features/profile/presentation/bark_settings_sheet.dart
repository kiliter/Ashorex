import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

/// 当前账号专属 Bark 设置，服务端不返回 Key，留空保留原值。
class BarkSettingsSheet extends ConsumerStatefulWidget {
  const BarkSettingsSheet({super.key});
  @override
  ConsumerState<BarkSettingsSheet> createState() => _BarkSettingsSheetState();
}

class _BarkSettingsSheetState extends ConsumerState<BarkSettingsSheet> {
  final _url = TextEditingController(text: 'https://api.day.app');
  final _key = TextEditingController();
  bool _enabled = false;
  bool _configured = false;
  bool _loading = true;
  bool _saving = false;
  String? _error;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final value = await ref
          .read(shanganRepositoryProvider)
          .loadBarkSettings();
      if (!mounted) return;
      setState(() {
        _url.text = value['baseUrl'] as String;
        _enabled = value['enabled'] == true;
        _configured = value['deviceKeyConfigured'] == true;
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = '读取 Bark 配置失败，请重新打开重试';
        });
      }
    }
  }

  /// 保存完成后才关闭面板，失败保留草稿便于用户修正。
  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(shanganRepositoryProvider)
          .saveBarkSettings(
            baseUrl: _url.text.trim(),
            deviceKey: _key.text.trim(),
            enabled: _enabled,
          );
      if (mounted) Navigator.pop(context);
    } catch (_) {
      if (mounted)
        setState(() => _error = '保存失败，请检查设备 Key 和网络；自建 HTTPS 服务需管理员加入可信源站');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  void dispose() {
    _url.dispose();
    _key.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SafeArea(
    child: SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(
        18,
        18,
        18,
        18 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            '我的 Bark 推送',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
          ),
          const Text('启用后，个人催办改走 Bark，替代 Server 酱。默认使用“上岸”分组、重要通知，点击打开 App。'),
          if (_loading && _error == null) const LinearProgressIndicator(),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('启用个人 Bark'),
            value: _enabled,
            onChanged: _loading || _saving
                ? null
                : (v) => setState(() => _enabled = v),
          ),
          TextField(
            controller: _url,
            enabled: !_loading && !_saving,
            decoration: const InputDecoration(
              labelText: 'Bark 服务地址（HTTPS，默认官方服务）',
            ),
          ),
          TextField(
            controller: _key,
            enabled: !_loading && !_saving,
            obscureText: true,
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: '设备 Key',
              hintText: _configured ? '已配置，留空保持原值' : '填写你的设备 Key',
            ),
          ),
          if (_error != null)
            Text(_error!, style: const TextStyle(color: Colors.red)),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _loading || _saving ? null : _save,
            child: Text(_saving ? '保存中…' : '保存'),
          ),
        ],
      ),
    ),
  );
}
