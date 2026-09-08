import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
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

  /// 初次打开和失败重试共用加载流程，失败后保留关闭与重试入口。
  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
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
          _loading = false;
          _error = '读取 Bark 配置失败，请重试';
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
      if (mounted) {
        setState(() => _error = '保存失败，请检查设备 Key 和网络；自建 HTTPS 服务需管理员加入可信源站');
      }
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

  /// 采用设置页相同的分组卡片；短标签与说明分开，键盘只压缩可滚动表单。
  @override
  Widget build(BuildContext context) => SafeArea(
    top: false,
    child: Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * .85,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 4, 8, 8),
              child: Row(
                children: [
                  const Expanded(
                    child: ShanganSheetHeader(
                      title: '我的 Bark 推送',
                      subtitle: '仅用于当前账号的个人催办',
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭 Bark 设置',
                    onPressed: _saving ? null : () => Navigator.pop(context),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
            ),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (_loading) const LinearProgressIndicator(),
                    ShanganCard(
                      child: Row(
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Text(
                                  '启用个人 Bark',
                                  style: TextStyle(
                                    fontSize: 14.5,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  _enabled ? '已启用 · 替代 Server 酱' : '未启用',
                                  style: const TextStyle(
                                    fontSize: 12.5,
                                    color: ShanganColors.mutedInk,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Switch(
                            value: _enabled,
                            onChanged: _loading || _saving
                                ? null
                                : (value) => setState(() => _enabled = value),
                          ),
                        ],
                      ),
                    ),
                    const ShanganGroupLabel('接收设备'),
                    const Text(
                      '服务地址',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: _url,
                      enabled: !_loading && !_saving,
                      keyboardType: TextInputType.url,
                      autocorrect: false,
                      enableSuggestions: false,
                      decoration: const InputDecoration(
                        hintText: 'https://api.day.app',
                      ),
                    ),
                    const SizedBox(height: 6),
                    const Text(
                      '默认使用 Bark 官方服务；自建地址需由管理员允许。',
                      style: TextStyle(
                        fontSize: 12,
                        height: 1.5,
                        color: ShanganColors.mutedInk,
                      ),
                    ),
                    const SizedBox(height: 16),
                    const Text(
                      '设备 Key',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: _key,
                      enabled: !_loading && !_saving,
                      obscureText: true,
                      autocorrect: false,
                      enableSuggestions: false,
                      decoration: InputDecoration(
                        hintText: _configured ? '已配置，留空保持原值' : '填写你的设备 Key',
                      ),
                    ),
                    const SizedBox(height: 16),
                    const Text(
                      '通知方式',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 8),
                    const Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        ShanganBadge(label: '上岸分组', tone: ShanganBadgeTone.ink),
                        ShanganBadge(label: '重要通知', tone: ShanganBadgeTone.ink),
                        ShanganBadge(
                          label: '点击打开 App',
                          tone: ShanganBadgeTone.ink,
                        ),
                      ],
                    ),
                    if (_error != null) ...[
                      const SizedBox(height: 12),
                      Text(
                        _error!,
                        style: const TextStyle(
                          color: ShanganColors.red,
                          height: 1.5,
                        ),
                      ),
                      if (_error == '读取 Bark 配置失败，请重试')
                        TextButton(onPressed: _load, child: const Text('重新加载')),
                    ],
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed:
                          _loading || _saving || _error == '读取 Bark 配置失败，请重试'
                          ? null
                          : _save,
                      child: Text(_saving ? '保存中…' : '保存'),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
