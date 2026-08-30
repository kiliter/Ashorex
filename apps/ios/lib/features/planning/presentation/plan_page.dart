import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/planning/presentation/abandon_plan_sheet.dart';
import 'package:shangan_ios/features/planning/presentation/lock_plan_sheet.dart';

/// 今日计划页根据服务端状态决定可编辑、锁定或开摆操作。
final class PlanPage extends ConsumerStatefulWidget {
  const PlanPage({super.key});

  @override
  ConsumerState<PlanPage> createState() => _PlanPageState();
}

final class _PlanPageState extends ConsumerState<PlanPage> {
  late Future<DailyPlanData> _future;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() => _future = ref.read(planRepositoryProvider).loadToday();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('今日计划')),
      body: FutureBuilder<DailyPlanData>(
        future: _future,
        builder: (context, snapshot) {
          if (!snapshot.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          final plan = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(
                '状态：${plan.status}',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 8),
              if (plan.items.isEmpty) const Text('今天还没有任务，可从课程详情加入视频。'),
              ...plan.items.map(
                (item) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(item.title),
                  subtitle: Text(
                    '${item.itemType} · ${item.completedSeconds}/${item.plannedSeconds} 秒',
                  ),
                  trailing: Text(item.status),
                  onTap: plan.status == 'LOCKED' && item.mediaItemId != null
                      ? () => context.push(
                          Uri(
                            path: '/player/${item.mediaItemId}',
                            queryParameters: {
                              'planItemId': item.id,
                              'title': item.title,
                            },
                          ).toString(),
                        )
                      : null,
                ),
              ),
              if (plan.status == 'DRAFT') ...[
                OutlinedButton.icon(
                  onPressed: _addFocus,
                  icon: const Icon(Icons.timer_outlined),
                  label: const Text('添加 25 分钟专注'),
                ),
                FilledButton(
                  onPressed: plan.items.isEmpty ? null : () => _showLock(plan),
                  child: const Text('开始今天'),
                ),
              ],
              if (plan.status == 'LOCKED')
                FilledButton.tonal(
                  onPressed: _showAbandon,
                  child: const Text('开摆'),
                ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _addFocus() async {
    await ref.read(planRepositoryProvider).addFocus('专注学习', 25 * 60);
    setState(_reload);
  }

  Future<void> _showLock(DailyPlanData plan) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => LockPlanSheet(
        plan: plan,
        onConfirm: () async {
          await ref.read(planRepositoryProvider).lockToday();
          if (sheetContext.mounted) Navigator.pop(sheetContext);
        },
      ),
    );
    if (mounted) setState(_reload);
  }

  Future<void> _showAbandon() async {
    final repository = ref.read(planRepositoryProvider);
    final preview = await repository.previewAbandon();
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => AbandonPlanSheet(
        preview: preview,
        onConfirm: (reason) async {
          await repository.abandon('OPEN_PALM', reason);
          if (sheetContext.mounted) Navigator.pop(sheetContext);
        },
      ),
    );
    if (mounted) setState(_reload);
  }
}
