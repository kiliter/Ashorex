import 'package:flutter/material.dart';
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
            Text('开始今天', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            Text('共 ${plan.items.length} 个任务，计划 ${(seconds / 60).ceil()} 分钟。'),
            const SizedBox(height: 8),
            const Text('锁定后不能增加、删除或调整任务。'),
            const SizedBox(height: 20),
            FilledButton(onPressed: onConfirm, child: const Text('确认锁定')),
          ],
        ),
      ),
    );
  }
}
