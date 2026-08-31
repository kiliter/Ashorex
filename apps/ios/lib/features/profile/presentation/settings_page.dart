import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
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
  String _aliveCheckLevel = 'NORMAL';
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
        _aliveCheckLevel = preferences.aliveCheckLevel;
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
              aliveCheckLevel: _aliveCheckLevel,
              dayEndLocalTime: _dayEndController.text,
            ),
          );
      if (!mounted) return;
      setState(() {
        _aliveCheckLevel = saved.aliveCheckLevel;
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
                  const ShanganEyebrow('验活等级'),
                  const SizedBox(height: 8),
                  RadioGroup<String>(
                    groupValue: _aliveCheckLevel,
                    onChanged: (value) {
                      if (value != null) {
                        setState(() => _aliveCheckLevel = value);
                      }
                    },
                    child: Column(
                      children: const [
                        _LevelOption(
                          value: 'OFF',
                          title: '关闭',
                          description: '不主动验活，仅记录可信播放进度',
                        ),
                        _LevelOption(
                          value: 'NORMAL',
                          title: '普通',
                          description: '每 40–60 分钟确认一次',
                        ),
                        _LevelOption(
                          value: 'STRICT',
                          title: '严格',
                          description: '每 20–40 分钟确认一次',
                        ),
                        _LevelOption(
                          value: 'INTENSE',
                          title: '拷打',
                          description: '每 10–25 分钟确认一次，适合冲刺期',
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
                      final match = RegExp(r'^(?:[01]\d|2[0-3]):[0-5]\d$')
                          .hasMatch(value ?? '');
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

/// 验活选项同时呈现频率说明，避免用户只凭等级名称猜测行为。
final class _LevelOption extends StatelessWidget {
  const _LevelOption({
    required this.value,
    required this.title,
    required this.description,
  });

  final String value;
  final String title;
  final String description;

  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.only(bottom: 8),
    decoration: BoxDecoration(
      color: ShanganColors.surface,
      border: Border.all(color: ShanganColors.rule),
      borderRadius: BorderRadius.circular(12),
    ),
    child: RadioListTile<String>(
      value: value,
      activeColor: ShanganColors.blue,
      title: Text(title, style: Theme.of(context).textTheme.titleMedium),
      subtitle: Text(description),
    ),
  );
}
