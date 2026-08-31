import 'package:flutter/material.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

/// 锁定前明确展示任务数和总计划时长。
final class LockPlanSheet extends StatelessWidget {
  const LockPlanSheet({required this.plan, required this.onConfirm, super.key});

  final DailyPlanData plan;
  final Future<void> Function() onConfirm;

  @override
  Widget build(BuildContext context) {
    final seconds = plan.items.fold<int>(
      0,
      (sum, item) => sum + item.plannedSeconds,
    );
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const ShanganEyebrow('锁定今日作战单'),
            const SizedBox(height: 7),
            Text(
              '共 ${plan.items.length} 项任务',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 12),
            Text('计划 ${shanganDuration(seconds)}。'),
            const SizedBox(height: 14),
            const ShanganNotice(
              title: '锁定后不能增加、删除或调整任务',
              message: '未完成的观看、答题与专注量会在结束今天时形成准确欠债。',
              tone: ShanganTagTone.warning,
            ),
            const SizedBox(height: 20),
            FilledButton(onPressed: onConfirm, child: const Text('确认锁定并开始')),
          ],
        ),
      ),
    );
  }
}
