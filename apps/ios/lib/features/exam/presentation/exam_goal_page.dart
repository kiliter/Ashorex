import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';

/// 首次考试目标设置页，必须至少选择一门已同步课程。
final class ExamGoalPage extends ConsumerStatefulWidget {
  const ExamGoalPage({super.key});

  @override
  ConsumerState<ExamGoalPage> createState() => _ExamGoalPageState();
}

final class _ExamGoalPageState extends ConsumerState<ExamGoalPage> {
  final _name = TextEditingController();
  final Set<String> _courseIds = {};
  late DateTime _examDate;
  late DateTime _targetDate;
  bool _saving = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    final today = DateUtils.dateOnly(DateTime.now());
    _examDate = today.add(const Duration(days: 90));
    _targetDate = _examDate.subtract(const Duration(days: 14));
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置考试目标')),
      body: FutureBuilder<List<CourseSummary>>(
        future: ref.read(catalogRepositoryProvider).listCourses(),
        builder: (context, snapshot) {
          if (!snapshot.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          final courses = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              TextField(
                controller: _name,
                decoration: const InputDecoration(labelText: '考试名称'),
              ),
              const SizedBox(height: 12),
              _DateTile(
                label: '考试日期',
                value: _examDate,
                onTap: () => _pickDate(isExamDate: true),
              ),
              _DateTile(
                label: '计划完成课程日期',
                value: _targetDate,
                onTap: () => _pickDate(isExamDate: false),
              ),
              const SizedBox(height: 16),
              Text('参与进度计算的课程', style: Theme.of(context).textTheme.titleMedium),
              if (courses.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 16),
                  child: Text('暂无课程，请先由管理员同步 Emby 课程。'),
                ),
              ...courses.map(
                (course) => CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(course.name),
                  value: _courseIds.contains(course.id),
                  onChanged: (selected) => setState(() {
                    if (selected == true) {
                      _courseIds.add(course.id);
                    } else {
                      _courseIds.remove(course.id);
                    }
                  }),
                ),
              ),
              if (_message != null) ...[
                const SizedBox(height: 8),
                Text(
                  _message!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              const SizedBox(height: 20),
              FilledButton(
                onPressed: _saving || courses.isEmpty ? null : _save,
                child: Text(_saving ? '保存中…' : '保存并进入首页'),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _pickDate({required bool isExamDate}) async {
    final initial = isExamDate ? _examDate : _targetDate;
    final selected = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateUtils.dateOnly(DateTime.now()),
      lastDate: DateTime(DateTime.now().year + 10),
    );
    if (selected == null) return;
    setState(() {
      if (isExamDate) {
        _examDate = selected;
        if (_targetDate.isAfter(selected)) {
          _targetDate = selected.subtract(const Duration(days: 14));
        }
      } else {
        _targetDate = selected;
      }
    });
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty || _courseIds.isEmpty) {
      setState(() => _message = '请填写考试名称并至少选择一门课程');
      return;
    }
    if (_targetDate.isAfter(_examDate)) {
      setState(() => _message = '计划完成课程日期不能晚于考试日期');
      return;
    }
    setState(() {
      _saving = true;
      _message = null;
    });
    try {
      await ref
          .read(examRepositoryProvider)
          .saveGoal(
            ExamGoalDraft(
              name: _name.text.trim(),
              examDate: _examDate,
              targetCompletionDate: _targetDate,
              reviewBufferDays: _examDate.difference(_targetDate).inDays,
              courseIds: _courseIds.toList(),
            ),
          );
      if (mounted) context.go('/home');
    } catch (_) {
      if (mounted) setState(() => _message = '考试目标保存失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}

final class _DateTile extends StatelessWidget {
  const _DateTile({
    required this.label,
    required this.value,
    required this.onTap,
  });

  final String label;
  final DateTime value;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      title: Text(label),
      subtitle: Text('${value.year}-${value.month}-${value.day}'),
      trailing: const Icon(Icons.calendar_today_outlined),
      onTap: onTap,
    );
  }
}
