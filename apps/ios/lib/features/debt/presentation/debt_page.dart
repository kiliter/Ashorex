import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

/// 欠债页只展示服务端结算结果；作战单不在这里执行局部写入。
final class DebtPage extends ConsumerWidget {
  const DebtPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('学习欠债')),
      body: FutureBuilder<List<LearningDebtData>>(
        future: ref.read(planRepositoryProvider).loadDebts(),
        builder: (context, snapshot) {
          if (!snapshot.hasData) {
            return const ShanganLoading('正在读取学习欠债');
          }
          final debts = snapshot.data!;
          if (debts.isEmpty) return const Center(child: Text('当前没有未还欠债'));
          final total = debts.fold<int>(
            0,
            (sum, debt) => sum + debt.remainingSeconds,
          );
          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
            children: [
              const ShanganEyebrow('当前学习欠债'),
              const SizedBox(height: 7),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      shanganDuration(total),
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                  ),
                  ShanganStatusTag(
                    '${debts.length} 项待偿还',
                    tone: ShanganTagTone.risk,
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                '欠债来自未完成的真实任务，会保留来源与剩余量。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 20),
              const Divider(color: ShanganColors.ink, thickness: 2),
              ...debts.indexed.map((entry) {
                final debt = entry.$2;
                return Container(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  decoration: const BoxDecoration(
                    border: Border(
                      bottom: BorderSide(color: ShanganColors.rule),
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          SizedBox(
                            width: 34,
                            child: Text(
                              '${entry.$1 + 1}'.padLeft(2, '0'),
                              style: shanganNumberStyle(
                                context,
                                fontSize: 11,
                              ).copyWith(color: ShanganColors.mutedInk),
                            ),
                          ),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  debt.title,
                                  style: Theme.of(
                                    context,
                                  ).textTheme.titleMedium,
                                ),
                                const SizedBox(height: 3),
                                Text(
                                  '${debt.debtType} · 剩余 ${debt.remainingSeconds} 秒',
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ),
                          ),
                          ShanganStatusTag(
                            debt.status,
                            tone: ShanganTagTone.risk,
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      const ShanganEyebrow('由可信学习记录自动核对偿还状态'),
                    ],
                  ),
                );
              }),
            ],
          );
        },
      ),
    );
  }
}
