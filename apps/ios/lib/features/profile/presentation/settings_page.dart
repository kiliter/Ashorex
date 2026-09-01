import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/profile/data/preferences_repository.dart';

/// 用户学习偏好页，所有修改最终由服务端校验并保存。
final class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key});

  @override
  ConsumerState<SettingsPage> createState() => _SettingsPageState();
}

final class _SettingsPageState extends ConsumerState<SettingsPage> {
  final _formKey = GlobalKey<FormState>();
  final _dayEndController = TextEditingController();
  String _timezone = 'Asia/Shanghai';
  bool _aliveCheckEnabled = true;
  double _aliveCheckIntervalPercent = 50;
  bool _loading = true;
  bool _saving = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    Future<void>.microtask(_load);
  }

  @override
  void dispose() {
    _dayEndController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final preferences = await ref.read(preferencesRepositoryProvider).load();
      if (!mounted) return;
      setState(() {
        _timezone = preferences.timezone;
        _aliveCheckEnabled = preferences.aliveCheckEnabled;
        _aliveCheckIntervalPercent = preferences.aliveCheckIntervalPercent
            .toDouble();
        _dayEndController.text = preferences.dayEndLocalTime;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _message = '偏好加载失败，请稍后重试';
      });
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _message = null;
    });
    try {
      final saved = await ref
          .read(preferencesRepositoryProvider)
          .update(
            UserPreferences(
              timezone: _timezone,
              aliveCheckEnabled: _aliveCheckEnabled,
              aliveCheckIntervalPercent: _aliveCheckIntervalPercent.round(),
              dayEndLocalTime: _dayEndController.text,
            ),
          );
      if (!mounted) return;
      setState(() {
        _aliveCheckEnabled = saved.aliveCheckEnabled;
        _aliveCheckIntervalPercent = saved.aliveCheckIntervalPercent.toDouble();
        _dayEndController.text = saved.dayEndLocalTime;
        _message = '设置已保存';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _message = '保存失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('学习偏好')),
      body: _loading
          ? const ShanganLoading('正在读取学习偏好')
          : Form(
              key: _formKey,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
                children: [
                  const ShanganEyebrow('视频进度验活'),
                  const SizedBox(height: 8),
                  ShanganSurface(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Material(
                          type: MaterialType.transparency,
                          child: SwitchListTile.adaptive(
                            key: const Key('aliveCheckEnabledSwitch'),
                            contentPadding: EdgeInsets.zero,
                            title: const Text('启用播放验活'),
                            subtitle: const Text('关闭后仍保存当前百分比设置'),
                            value: _aliveCheckEnabled,
                            onChanged: (value) {
                              setState(() => _aliveCheckEnabled = value);
                            },
                          ),
                        ),
                        const Divider(),
                        AnimatedOpacity(
                          opacity: _aliveCheckEnabled ? 1 : 0.45,
                          duration: const Duration(milliseconds: 160),
                          child: IgnorePointer(
                            ignoring: !_aliveCheckEnabled,
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                Text(
                                  '每推进 ${_aliveCheckIntervalPercent.round()}% 验活一次',
                                  style: Theme.of(
                                    context,
                                  ).textTheme.titleMedium,
                                ),
                                const SizedBox(height: 4),
                                const Text('按视频内容位置触发，倍速播放不会改变检查点。'),
                                Slider(
                                  key: const Key('aliveCheckPercentSlider'),
                                  min: 1,
                                  max: 50,
                                  divisions: 49,
                                  label:
                                      '${_aliveCheckIntervalPercent.round()}%',
                                  value: _aliveCheckIntervalPercent,
                                  onChanged: (value) {
                                    setState(
                                      () => _aliveCheckIntervalPercent = value,
                                    );
                                  },
                                ),
                                const Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceBetween,
                                  children: [Text('1% · 频繁'), Text('50% · 默认')],
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                  TextFormField(
                    key: const Key('dayEndField'),
                    controller: _dayEndController,
                    keyboardType: TextInputType.datetime,
                    decoration: const InputDecoration(
                      labelText: '日终时间',
                      hintText: '23:59',
                      helperText: '使用 24 小时制 HH:mm',
                    ),
                    validator: (value) {
                      final match = RegExp(
                        r'^(?:[01]\d|2[0-3]):[0-5]\d$',
                      ).hasMatch(value ?? '');
                      return match ? null : '请输入有效的 24 小时时间';
                    },
                  ),
                  const SizedBox(height: 14),
                  ShanganNotice(
                    title: '用户时区 $_timezone',
                    message: '日终关闭、日报和周报边界都按服务端保存的 IANA 时区计算。',
                  ),
                  if (_message != null) ...[
                    const SizedBox(height: 16),
                    Text(_message!, textAlign: TextAlign.center),
                  ],
                  const SizedBox(height: 24),
                  SizedBox(
                    height: 52,
                    child: FilledButton(
                      key: const Key('savePreferences'),
                      onPressed: _saving ? null : _save,
                      child: Text(_saving ? '保存中…' : '保存设置'),
                    ),
                  ),
                ],
              ),
            ),
    );
  }
}
