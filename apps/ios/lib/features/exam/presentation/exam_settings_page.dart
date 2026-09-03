import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';

/// 「我的」中的考试列表，按到期日从近到远排列。
final class ExamSettingsPage extends ConsumerWidget {
  const ExamSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('考试设置')),
      floatingActionButton: FloatingActionButton.extended(
        key: const Key('addExamGoal'),
        onPressed: () => context.push('/exam-goal?edit=true'),
        icon: const Icon(Icons.add),
        label: const Text('新增考试'),
      ),
      body: FutureBuilder<List<ExamGoal>>(
        future: ref.read(examRepositoryProvider).listGoals(),
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
                child: ShanganNavRow(
                  key: Key('examSetting-${goal.id}'),
                  icon: Icons.flag_outlined,
                  title: goal.name,
                  subtitle: '考试日 $examDay',
                  onTap: () =>
                      context.push('/exam-goal?edit=true&id=${goal.id}'),
                ),
              );
            },
          );
        },
      ),
    );
  }
}
