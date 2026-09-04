import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';

/// 考试目标设置页；首次进入不能返回，从「我的」进入时可修改后返回。
final class ExamGoalPage extends ConsumerStatefulWidget {
  const ExamGoalPage({super.key, this.allowBack = false, this.goalId});

  final bool allowBack;
  final String? goalId;

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
    WidgetsBinding.instance.addPostFrameCallback((_) => _loadExisting());
  }

  Future<void> _loadExisting() async {
    final repository = ref.read(examRepositoryProvider);
    final ExamGoal? goal;
    if (widget.goalId != null) {
      goal = await repository.loadGoalById(widget.goalId!);
    } else if (widget.allowBack) {
      goal = null;
    } else {
      goal = await repository.loadGoal();
    }
    final loaded = goal;
    if (!mounted || loaded == null) return;
    setState(() {
      _name.text = loaded.name;
      _examDate = DateUtils.dateOnly(loaded.examDate);
      _targetDate = DateUtils.dateOnly(loaded.targetCompletionDate);
      _courseIds
        ..clear()
        ..addAll(loaded.courseIds);
    });
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.allowBack ? '考试设置' : '考试目标'),
        automaticallyImplyLeading: widget.allowBack,
      ),
      body: FutureBuilder<List<CourseSummary>>(
        future: ref.read(catalogRepositoryProvider).listCourses(),
        builder: (context, snapshot) {
          if (!snapshot.hasData) {
            return const ShanganLoading('正在读取可选课程');
          }
          final courses = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
            children: [
              ShanganEyebrow(widget.allowBack ? '考试设置' : '首次设置 · 01/03'),
              const SizedBox(height: 7),
              Text('把终点写清楚', style: Theme.of(context).textTheme.headlineMedium),
              const SizedBox(height: 8),
              Text(
                '课程完成日应早于考试日，为复习和模拟留出缓冲。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 22),
              TextField(
                controller: _name,
                decoration: const InputDecoration(labelText: '考试名称'),
              ),
              const SizedBox(height: 14),
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
              ShanganNotice(
                title: '当前复习缓冲 ${_examDate.difference(_targetDate).inDays} 天',
                message: '这段时间用于二轮复习、模拟考试和错题回看。',
              ),
              const SizedBox(height: 20),
              const ShanganEyebrow('参与进度计算的课程'),
              const SizedBox(height: 10),
              if (courses.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text('暂无课程，请先由管理员同步 Emby 课程。'),
                )
              else ...[
                OutlinedButton.icon(
                  key: const Key('selectExamCourses'),
                  onPressed: () => _openCoursePicker(courses),
                  icon: const Icon(Icons.menu_book_outlined),
                  label: Text(
                    _courseIds.isEmpty ? '选择课程' : '已选 ${_courseIds.length} 门课程',
                  ),
                ),
              ],
              if (_courseIds.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(
                  courses
                      .where((course) => _courseIds.contains(course.id))
                      .map((course) => course.name)
                      .join('、'),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
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
                child: Text(
                  _saving ? '保存中…' : (widget.allowBack ? '保存考试设置' : '保存并进入首页'),
                ),
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

  /// 底部弹层勾选课程，确定后写回表单，仍由页面保存按钮提交服务端。
  Future<void> _openCoursePicker(List<CourseSummary> courses) async {
    final confirmed = await showModalBottomSheet<Set<String>>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (context) => _ExamCoursePickerSheet(
        courses: courses,
        initiallySelected: _courseIds,
      ),
    );
    if (!mounted || confirmed == null) return;
    setState(() {
      _courseIds
        ..clear()
        ..addAll(confirmed);
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
      final draft = ExamGoalDraft(
        name: _name.text.trim(),
        examDate: _examDate,
        targetCompletionDate: _targetDate,
        reviewBufferDays: _examDate.difference(_targetDate).inDays,
        courseIds: _courseIds.toList(),
      );
      final repository = ref.read(examRepositoryProvider);
      if (widget.goalId != null) {
        await repository.updateGoal(widget.goalId!, draft);
      } else {
        await repository.saveGoal(draft);
      }
      if (!mounted) return;
      if (widget.allowBack) {
        context.pop();
      } else {
        context.go('/home');
      }
    } catch (error) {
      if (mounted) {
        setState(() => _message = shanganErrorMessage(error, '考试目标保存失败，请稍后重试'));
      }
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
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: InputDecorator(
          decoration: InputDecoration(labelText: label),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '${value.year}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}',
                  style: shanganNumberStyle(context, fontSize: 14),
                ),
              ),
              const Icon(
                Icons.calendar_today_outlined,
                color: ShanganColors.blue,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 考试选课底部弹层：本地模糊搜索 + 固定高度滚动列表。
final class _ExamCoursePickerSheet extends StatefulWidget {
  const _ExamCoursePickerSheet({
    required this.courses,
    required this.initiallySelected,
  });

  final List<CourseSummary> courses;
  final Set<String> initiallySelected;

  @override
  State<_ExamCoursePickerSheet> createState() => _ExamCoursePickerSheetState();
}

final class _ExamCoursePickerSheetState extends State<_ExamCoursePickerSheet> {
  late final Set<String> _draft = Set<String>.from(widget.initiallySelected);
  final _query = TextEditingController();
  final _scroll = ScrollController();

  @override
  void dispose() {
    _query.dispose();
    _scroll.dispose();
    super.dispose();
  }

  String _normalize(String value) =>
      value.toLowerCase().replaceAll(RegExp(r'\s+'), '');

  @override
  Widget build(BuildContext context) {
    final needle = _normalize(_query.text);
    final visible = needle.isEmpty
        ? widget.courses
        : widget.courses
              .where((course) => _normalize(course.name).contains(needle))
              .toList();
    final height = MediaQuery.sizeOf(context).height * 0.72;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SizedBox(
        height: height,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '选择课程',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  Text(
                    '已选 ${_draft.length} 门',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                key: const Key('examCourseSearch'),
                controller: _query,
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  hintText: '搜索课程名称',
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border.all(color: ShanganColors.rule, width: 1.5),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: visible.isEmpty
                      ? const Center(child: Text('没有匹配的课程'))
                      : ShanganIdleScrollbar(
                          controller: _scroll,
                          child: ListView.separated(
                            controller: _scroll,
                            itemCount: visible.length,
                            separatorBuilder: (_, _) =>
                                const Divider(height: 1),
                            itemBuilder: (context, index) {
                              final course = visible[index];
                              final checked = _draft.contains(course.id);
                              return CheckboxListTile(
                                key: Key('exam-course-${course.id}'),
                                title: Text(course.name),
                                value: checked,
                                activeColor: ShanganColors.blue,
                                onChanged: (selected) {
                                  setState(() {
                                    if (selected == true) {
                                      _draft.add(course.id);
                                    } else {
                                      _draft.remove(course.id);
                                    }
                                  });
                                },
                              );
                            },
                          ),
                        ),
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('取消'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: FilledButton(
                      key: const Key('confirmExamCourses'),
                      onPressed: () => Navigator.pop(context, _draft),
                      child: const Text('确定'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
