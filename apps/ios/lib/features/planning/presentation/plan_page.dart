import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
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
            return const ShanganLoading('正在核对今日作战单');
          }
          final plan = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        ShanganEyebrow(
                          '${_planDate(plan.date)} · ${plan.status == 'DRAFT' ? '可调整' : '以服务端状态为准'}',
                        ),
                        const SizedBox(height: 7),
                        Text(
                          '今日作战单',
                          style: Theme.of(context).textTheme.headlineMedium,
                        ),
                      ],
                    ),
                  ),
                  ShanganStatusTag(
                    _planLabel(plan.status),
                    tone: _planTone(plan.status),
                  ),
                ],
              ),
              const SizedBox(height: 18),
              const Divider(color: ShanganColors.ink, thickness: 2),
              if (plan.items.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 20),
                  child: ShanganNotice(
                    title: '今天还没有任务',
                    message: '从课程详情加入视频，或添加一个专注任务。',
                  ),
                ),
              ...plan.items.indexed.map((entry) {
                final item = entry.$2;
                final progress = item.plannedSeconds == 0
                    ? 0.0
                    : item.completedSeconds / item.plannedSeconds;
                return InkWell(
                  onTap: plan.status == 'LOCKED' ? () => _openItem(item) : null,
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 15),
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
                            Container(
                              width: 28,
                              height: 28,
                              alignment: Alignment.center,
                              decoration: BoxDecoration(
                                color: ShanganColors.blueSoft,
                                border: Border.all(
                                  color: ShanganColors.blue,
                                  width: 1.5,
                                ),
                                borderRadius: BorderRadius.circular(9),
                              ),
                              child: Text(
                                '${entry.$1 + 1}'.padLeft(2, '0'),
                                style: shanganNumberStyle(
                                  context,
                                  fontSize: 10,
                                ).copyWith(color: ShanganColors.blue),
                              ),
                            ),
                            const SizedBox(width: 11),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    item.title,
                                    style: Theme.of(context)
                                        .textTheme
                                        .titleMedium,
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    '${_itemTypeLabel(item.itemType)} · ${shanganDuration(item.plannedSeconds)}',
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodySmall,
                                  ),
                                ],
                              ),
                            ),
                            ShanganStatusTag(
                              _itemStatusLabel(item.status),
                              tone: item.status == 'COMPLETED'
                                  ? ShanganTagTone.success
                                  : ShanganTagTone.neutral,
                            ),
                          ],
                        ),
                        const SizedBox(height: 10),
                        Padding(
                          padding: const EdgeInsets.only(left: 39),
                          child: ShanganProgress(value: progress),
                        ),
                      ],
                    ),
                  ),
                );
              }),
              const SizedBox(height: 20),
              if (plan.status == 'DRAFT') ...[
                OutlinedButton.icon(
                  onPressed: _addFocus,
                  icon: const Icon(Icons.timer_outlined),
                  label: const Text('添加 25 分钟专注'),
                ),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: plan.items.isEmpty ? null : () => _showLock(plan),
                  child: const Text('开始今天'),
                ),
              ],
              if (plan.status == 'LOCKED')
                OutlinedButton(
                  onPressed: _showAbandon,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: ShanganColors.red,
                    side: const BorderSide(
                      color: ShanganColors.red,
                      width: 1.5,
                    ),
                  ),
                  child: const Text('结束今天并开摆'),
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

  /// 按任务组件进入对应学习页面；无媒体的还债任务在 V1 中只可能是 FOCUS。
  void _openItem(PlanItemData item) {
    final focusTask =
        item.itemType == 'FOCUS' ||
        (item.itemType == 'DEBT_REPAYMENT' && item.mediaItemId == null);
    if (focusTask) {
      context.push(
        Uri(
          path: '/focus',
          queryParameters: {
            'planItemId': item.id,
            'title': item.title,
            'plannedSeconds': '${item.plannedSeconds}',
          },
        ).toString(),
      );
      return;
    }
    final mediaItemId = item.mediaItemId;
    if (mediaItemId == null) return;
    context.push(
      Uri(
        path: '/player/$mediaItemId',
        queryParameters: {'planItemId': item.id, 'title': item.title},
      ).toString(),
    );
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

String _planLabel(String status) => switch (status) {
  'DRAFT' => '草稿',
  'LOCKED' => '已锁定',
  'COMPLETED' => '已完成',
  'ABANDONED' => '已开摆',
  'CLOSED_WITH_DEBT' => '已结算欠债',
  _ => status,
};

ShanganTagTone _planTone(String status) => switch (status) {
  'LOCKED' => ShanganTagTone.warning,
  'COMPLETED' => ShanganTagTone.success,
  'ABANDONED' || 'CLOSED_WITH_DEBT' => ShanganTagTone.risk,
  _ => ShanganTagTone.neutral,
};

String _itemTypeLabel(String type) => switch (type) {
  'VIDEO' => '视频学习',
  'FOCUS' => '专注计时',
  'QUIZ' => '独立答题',
  'DEBT_REPAYMENT' => '偿还欠债',
  _ => type,
};

String _itemStatusLabel(String status) => switch (status) {
  'PENDING' => '待开始',
  'IN_PROGRESS' => '进行中',
  'COMPLETED' => '已完成',
  _ => status,
};

String _planDate(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}/'
    '${value.month.toString().padLeft(2, '0')}/'
    '${value.day.toString().padLeft(2, '0')}';
