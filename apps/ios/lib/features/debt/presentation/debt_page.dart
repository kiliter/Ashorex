import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

/// 欠债页只能查询和加入 DRAFT 计划，不能直接核销。
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
            return const Center(child: CircularProgressIndicator());
          }
          final debts = snapshot.data!;
          if (debts.isEmpty) return const Center(child: Text('当前没有未还欠债'));
          return ListView.separated(
            itemCount: debts.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final debt = debts[index];
              return ListTile(
                minTileHeight: 72,
                title: Text(debt.title),
                subtitle: Text(
                  '${debt.debtType} · 剩余 ${debt.remainingSeconds} 秒',
                ),
                trailing: TextButton(
                  onPressed: () async {
                    await ref.read(planRepositoryProvider).addDebtItems([
                      debt.id,
                    ]);
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('已加入今日 DRAFT 计划')),
                      );
                    }
                  },
                  child: const Text('加入计划'),
                ),
              );
            },
          );
        },
      ),
    );
  }
}
