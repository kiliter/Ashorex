import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/dashboard/presentation/home_page.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';

/// 「我的」中的考试列表，按到期日从近到远排列，并支持新增、修改和删除。
final class ExamSettingsPage extends ConsumerStatefulWidget {
  const ExamSettingsPage({super.key});

  @override
  ConsumerState<ExamSettingsPage> createState() => _ExamSettingsPageState();
}

final class _ExamSettingsPageState extends ConsumerState<ExamSettingsPage> {
  late Future<List<ExamGoal>> _goals;

  @override
  void initState() {
    super.initState();
    _goals = ref.read(examRepositoryProvider).listGoals();
  }

  void _reload() {
    setState(() {
      _goals = ref.read(examRepositoryProvider).listGoals();
    });
  }

  /// 删除前必须二次确认；确认后同时刷新本页和首页考试卡片。
  Future<void> _confirmDelete(ExamGoal goal) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除考试目标'),
        content: Text(
          '删除「${goal.name}」后，首页不再计算这场考试的学习压力。'
          '已完成的学习记录、可信进度和学习欠债不会被删除。',
        ),
        actions: [
          TextButton(
            key: const Key('cancelDeleteExamGoal'),
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: const Key('confirmDeleteExamGoal'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      await ref.read(examRepositoryProvider).deleteGoal(goal.id);
      if (!mounted) return;
      bumpHomeRefresh();
      _reload();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(shanganErrorMessage(error, '考试目标删除失败，请稍后重试'))),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('考试设置')),
      floatingActionButton: FloatingActionButton.extended(
        key: const Key('addExamGoal'),
        onPressed: () async {
          await context.push('/exam-goal?edit=true');
          if (mounted) _reload();
        },
        icon: const Icon(Icons.add),
        label: const Text('新增考试'),
      ),
      body: FutureBuilder<List<ExamGoal>>(
        future: _goals,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const ShanganLoading('正在读取考试目标');
          }
          final goals = [...?snapshot.data]
            ..sort((left, right) => left.examDate.compareTo(right.examDate));
          if (goals.isEmpty) {
            return const Padding(
              padding: shanganPagePadding,
              child: ShanganSurface(
                borderColor: ShanganColors.blue,
                child: ShanganNotice(
                  title: '还没有考试目标',
                  message: '先新增一场考试，首页会按到期日排列进度。',
                ),
              ),
            );
          }
          return ListView.separated(
            padding: shanganPagePadding,
            itemCount: goals.length,
            separatorBuilder: (_, _) => const SizedBox(height: 12),
            itemBuilder: (context, index) {
              final goal = goals[index];
              final examDay =
                  '${goal.examDate.year}-${goal.examDate.month.toString().padLeft(2, '0')}-${goal.examDate.day.toString().padLeft(2, '0')}';
              return ShanganSurface(
                borderColor: ShanganColors.blue,
                padding: const EdgeInsets.fromLTRB(10, 6, 10, 6),
                child: Row(
                  children: [
                    Expanded(
                      child: ShanganNavRow(
                        key: Key('examSetting-${goal.id}'),
                        icon: Icons.flag_outlined,
                        title: goal.name,
                        subtitle: '考试日 $examDay',
                        onTap: () async {
                          await context.push(
                            '/exam-goal?edit=true&id=${goal.id}',
                          );
                          if (mounted) _reload();
                        },
                      ),
                    ),
                    IconButton(
                      key: Key('deleteExamGoal-${goal.id}'),
                      tooltip: '删除考试目标',
                      onPressed: () => _confirmDelete(goal),
                      icon: const Icon(
                        Icons.delete_outline,
                        color: ShanganColors.red,
                      ),
                    ),
                  ],
                ),
              );
            },
          );
        },
      ),
    );
  }
}
