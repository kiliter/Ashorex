import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/focus/data/mock_exam_repository.dart';

/// 系统设置中的模拟考试预置管理页，作战单只选择预置而不临时输入时长。
final class MockExamPresetPage extends ConsumerStatefulWidget {
  const MockExamPresetPage({super.key});

  @override
  ConsumerState<MockExamPresetPage> createState() => _MockExamPresetPageState();
}

final class _MockExamPresetPageState extends ConsumerState<MockExamPresetPage> {
  late Future<List<MockExamPresetData>> _future;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    _future = ref.read(mockExamRepositoryProvider).listPresets();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('模拟考试预置')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _editPreset(),
        icon: const Icon(Icons.add),
        label: const Text('新增预置'),
      ),
      body: FutureBuilder<List<MockExamPresetData>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const ShanganLoading('正在读取模拟考试预置');
          }
          if (snapshot.hasError) {
            return Center(
              child: OutlinedButton.icon(
                onPressed: () => setState(_reload),
                icon: const Icon(Icons.refresh),
                label: const Text('加载失败，重新加载'),
              ),
            );
          }
          final presets = snapshot.data ?? const [];
          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 108),
            children: [
              ShanganSurface(
                borderColor: ShanganColors.blue,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const ShanganEyebrow('作战单可选项'),
                    const SizedBox(height: 8),
                    Text(
                      '预先定义考试名称和时长',
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '开始考试后由服务端记录截止时间；到时或提前交卷后，上传至少一张试卷照片才算完成。',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              if (presets.isEmpty)
                const Padding(
                  padding: EdgeInsets.only(top: 16),
                  child: ShanganSurface(
                    borderColor: ShanganColors.blue,
                    child: ShanganNotice(
                      title: '还没有考试预置',
                      message: '例如“行测模拟卷 · 120 分钟”或“申论模拟卷 · 180 分钟”。',
                    ),
                  ),
                ),
              ...presets.indexed.map((entry) {
                final preset = entry.$2;
                return Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: ShanganSurface(
                    borderColor: ShanganColors.blue,
                    padding: const EdgeInsets.fromLTRB(14, 8, 6, 8),
                    child: Row(
                      children: [
                        Container(
                          width: 40,
                          height: 40,
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                            color: ShanganColors.blueSoft,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Text(
                            '${entry.$1 + 1}'.padLeft(2, '0'),
                            style: shanganNumberStyle(
                              context,
                              fontSize: 13,
                            ).copyWith(color: ShanganColors.blue),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                preset.name,
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                              const SizedBox(height: 4),
                              Text(
                                shanganDuration(preset.durationSeconds),
                                style: Theme.of(context).textTheme.bodySmall,
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          tooltip: '编辑',
                          onPressed: () => _editPreset(preset),
                          icon: const Icon(Icons.edit_outlined),
                        ),
                        IconButton(
                          tooltip: '删除',
                          onPressed: () => _deletePreset(preset),
                          icon: const Icon(Icons.delete_outline),
                          color: ShanganColors.red,
                        ),
                      ],
                    ),
                  ),
                );
              }),
            ],
          );
        },
      ),
    );
  }

  Future<void> _editPreset([MockExamPresetData? preset]) async {
    final submitted = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => _MockExamPresetEditorSheet(
        repository: ref.read(mockExamRepositoryProvider),
        preset: preset,
      ),
    );
    if (submitted == true && mounted) setState(_reload);
  }

  Future<void> _deletePreset(MockExamPresetData preset) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('删除考试预置？'),
        content: Text('“${preset.name}”将不再出现在作战单选择器中。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await ref.read(mockExamRepositoryProvider).deletePreset(preset.id);
    if (mounted) setState(_reload);
  }
}

/// 独立编辑面板自行持有输入控制器，避免关闭动画期间父页面提前释放控制器。
final class _MockExamPresetEditorSheet extends StatefulWidget {
  const _MockExamPresetEditorSheet({
    required this.repository,
    required this.preset,
  });

  final MockExamRepository repository;
  final MockExamPresetData? preset;

  @override
  State<_MockExamPresetEditorSheet> createState() =>
      _MockExamPresetEditorSheetState();
}

final class _MockExamPresetEditorSheetState
    extends State<_MockExamPresetEditorSheet> {
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController = TextEditingController(
    text: widget.preset?.name,
  );
  late final TextEditingController _minutesController = TextEditingController(
    text: widget.preset == null
        ? '120'
        : '${(widget.preset!.durationSeconds / 60).round()}',
  );
  bool _saving = false;
  String? _errorMessage;

  @override
  void dispose() {
    _nameController.dispose();
    _minutesController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(
        20,
        20,
        20,
        20 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              widget.preset == null ? '新增考试预置' : '编辑考试预置',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 18),
            TextFormField(
              key: const Key('mockExamPresetNameField'),
              controller: _nameController,
              autofocus: true,
              maxLength: 80,
              decoration: const InputDecoration(labelText: '考试名称'),
              validator: (value) =>
                  value == null || value.trim().isEmpty ? '请输入考试名称' : null,
            ),
            const SizedBox(height: 10),
            TextFormField(
              key: const Key('mockExamPresetMinutesField'),
              controller: _minutesController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: '考试时长（分钟）',
                helperText: '1–720 分钟',
              ),
              validator: (value) {
                final minutes = int.tryParse(value ?? '');
                return minutes == null || minutes < 1 || minutes > 720
                    ? '请输入 1–720 之间的分钟数'
                    : null;
              },
            ),
            if (_errorMessage != null) ...[
              const SizedBox(height: 8),
              Text(
                _errorMessage!,
                style: const TextStyle(color: ShanganColors.red),
              ),
            ],
            const SizedBox(height: 20),
            FilledButton(
              key: const Key('saveMockExamPreset'),
              onPressed: _saving ? null : _save,
              child: Text(_saving ? '正在保存' : '保存预置'),
            ),
          ],
        ),
      ),
    );
  }

  /// 保存异常只在当前面板中展示中文提示，不能再逸出为 Flutter 红屏。
  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _errorMessage = null;
    });
    try {
      final minutes = int.parse(_minutesController.text);
      final preset = widget.preset;
      if (preset == null) {
        await widget.repository.createPreset(
          _nameController.text.trim(),
          minutes * 60,
        );
      } else {
        await widget.repository.updatePreset(
          preset,
          _nameController.text.trim(),
          minutes * 60,
        );
      }
      if (mounted) Navigator.pop(context, true);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _errorMessage = shanganErrorMessage(error, '保存失败，请检查服务连接后重试');
      });
    }
  }
}
