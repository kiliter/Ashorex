import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
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
          ? const Center(child: CircularProgressIndicator())
          : Form(
              key: _formKey,
              child: ListView(
                padding: const EdgeInsets.all(20),
                children: [
                  DropdownButtonFormField<String>(
                    initialValue: _aliveCheckLevel,
                    decoration: const InputDecoration(labelText: '验活等级'),
                    items: const [
                      DropdownMenuItem(value: 'OFF', child: Text('关闭')),
                      DropdownMenuItem(value: 'NORMAL', child: Text('普通')),
                      DropdownMenuItem(value: 'STRICT', child: Text('严格')),
                      DropdownMenuItem(value: 'INTENSE', child: Text('拷打')),
                    ],
                    onChanged: (value) {
                      if (value != null) {
                        setState(() => _aliveCheckLevel = value);
                      }
                    },
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
