import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/layout/pad_chrome.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// Pad 首页四格指标：完成 / 观看 / 专注 / 附件，只消费当日服务端汇总。
final class PadHomeMetrics extends ConsumerWidget {
  const PadHomeMetrics({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ref
        .watch(dayViewProvider)
        .maybeWhen(
          data: (view) {
            final remaining = view.totals.pending;
            return Row(
              children: [
                Expanded(
                  child: PadMetricCard(
                    label: view.history ? '当日完成' : '今日完成',
                    value: '${view.totals.done}/${view.totals.total}',
                    note: '剩余 $remaining 项待办',
                    icon: Icons.check_circle_outline,
                    emphasized: true,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: PadMetricCard(
                    label: view.history ? '观看' : '观看时长',
                    value: formatPadMetricDuration(view.totals.watchedMs),
                    note: '今天的课程学习',
                    icon: Icons.play_arrow_rounded,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: PadMetricCard(
                    label: view.history ? '专注' : '专注时长',
                    value: formatPadMetricDuration(view.totals.focusedMs),
                    note: '今天的专注计时',
                    icon: Icons.timer_outlined,
                    accent: ShanganColors.ochre,
                    accentSoft: ShanganColors.ochreSoft,
                    emphasized: true,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: PadMetricCard(
                    label: view.history ? '未完成' : '附件',
                    value: view.history
                        ? '$remaining'
                        : '${view.totals.attachmentCount}',
                    note: view.history ? '仍可继续完成' : '今天的课程附件',
                    icon: Icons.insert_drive_file_outlined,
                  ),
                ),
              ],
            );
          },
          orElse: () => const SizedBox(height: PadChrome.metricMinHeight),
        );
  }
}

/// Pad 首页右侧：考试目标倒计时 + 今日节奏，不增加任何接口。
final class PadHomeAside extends ConsumerWidget {
  const PadHomeAside({this.onOpenStats, super.key});

  final VoidCallback? onOpenStats;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SingleChildScrollView(
      child: Column(
        children: [
          const _PadGoalCard(),
          const SizedBox(height: 17),
          _PadRhythmCard(onOpenStats: onOpenStats),
          const SizedBox(height: 12),
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 6),
            child: Text(
              '不必一天走很远，重要的是每天都在向前。',
              style: TextStyle(
                fontSize: 9,
                height: 1.8,
                color: ShanganColors.mutedInk,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 原型 `.home-aside .goal-board`：大号天数 + 渐变底，不伪造备考百分比。
final class _PadGoalCard extends ConsumerWidget {
  const _PadGoalCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final goals = ref.watch(goalsProvider);
    return PadSurface(
      minHeight: 230,
      padding: const EdgeInsets.fromLTRB(19, 17, 19, 18),
      borderColor: ShanganColors.blueLine,
      gradient: const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFEDF3FC), Color(0xFFF9FBFF), Color(0xFFFFFFFF)],
        stops: [0, 0.7, 1],
      ),
      child: goals.when(
        loading: () => const SizedBox(
          height: 120,
          child: Center(child: CircularProgressIndicator()),
        ),
        error: (error, _) => Text('目标加载失败：$error'),
        data: (list) {
          if (list.isEmpty) {
            return GestureDetector(
              onTap: () => context.push('/goals'),
              child: const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '我的考试目标',
                    style: TextStyle(
                      fontSize: 10,
                      letterSpacing: 0.4,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                  SizedBox(height: 18),
                  Text(
                    '还没有考试目标',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                  ),
                  SizedBox(height: 8),
                  Text(
                    '点这里新建一个倒计时',
                    style: TextStyle(
                      fontSize: 11,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ],
              ),
            );
          }
          final primary = list.firstWhere(
            (goal) => goal.primary,
            orElse: () => list.first,
          );
          final others = list
              .where((goal) => goal.id != primary.id)
              .toList(growable: false);
          return Stack(
            children: [
              const Positioned(
                right: 0,
                top: 30,
                child: Icon(
                  Icons.flag_outlined,
                  size: 88,
                  color: Color(0x2A2C68B7),
                ),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          '我的考试目标',
                          style: TextStyle(
                            fontSize: 10,
                            letterSpacing: 0.4,
                            color: ShanganColors.mutedInk,
                          ),
                        ),
                      ),
                      GestureDetector(
                        onTap: () => context.push('/goals'),
                        child: const Text(
                          '管理目标 ›',
                          style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.w600,
                            color: ShanganColors.blue,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 13),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.baseline,
                    textBaseline: TextBaseline.alphabetic,
                    children: [
                      Text(
                        '${primary.daysRemaining}',
                        style: const TextStyle(
                          fontSize: 54,
                          height: 1.05,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -3,
                          color: ShanganColors.blue,
                          fontFeatures: [FontFeature.tabularFigures()],
                        ),
                      ),
                      const SizedBox(width: 10),
                      const Text(
                        '天后见分晓',
                        style: TextStyle(
                          fontSize: 9,
                          color: ShanganColors.blue,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Container(
                        width: 5,
                        height: 5,
                        decoration: const BoxDecoration(
                          color: ShanganColors.red,
                          shape: BoxShape.circle,
                        ),
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          primary.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 14,
                            height: 1.6,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '目标日 ${formatIsoDate(primary.examDate).replaceAll('-', '.')} · 周${weekdayLabel(primary.examDate)}',
                    style: const TextStyle(
                      fontSize: 9,
                      height: 1.8,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                  if (others.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    for (final goal in others)
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: _PadOtherGoalRow(goal: goal),
                      ),
                  ],
                ],
              ),
            ],
          );
        },
      ),
    );
  }
}

/// Pad 侧栏次目标：名称、日期和剩余天数，口径与手机迷你目标格相同。
final class _PadOtherGoalRow extends StatelessWidget {
  const _PadOtherGoalRow({required this.goal});

  final ExamGoal goal;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: ShanganColors.surface,
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: ShanganColors.hair),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  goal.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(
                  '${goal.examDate.month.toString().padLeft(2, '0')}-'
                  '${goal.examDate.day.toString().padLeft(2, '0')}',
                  style: const TextStyle(
                    fontSize: 10,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ),
          ),
          Text(
            '${goal.daysRemaining}',
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w800,
              letterSpacing: -0.6,
              color: ShanganColors.ink,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

/// 原型 `#pad-home-rhythm`：用当日 Todo 完成比画圆环，并按类型拆开计数。
final class _PadRhythmCard extends ConsumerWidget {
  const _PadRhythmCard({this.onOpenStats});

  final VoidCallback? onOpenStats;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ref
        .watch(dayViewProvider)
        .maybeWhen(
          data: (view) {
            final total = view.todos.length;
            final done = view.totals.done.clamp(0, total);
            final remaining = (total - done).clamp(0, total);
            final progress = total == 0 ? 0.0 : done / total;
            final copy = total == 0
                ? ('这一天，暂时留白', '所选日期暂无学习记录。')
                : done == total
                ? ('今天的计划完成啦', '认真完成的每一步，都算数。')
                : ('把计划，一件件完成', '还剩 $remaining 项，按自己的节奏继续。');
            return PadSurface(
              padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          '今日节奏',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                      Text(
                        '${total == 0 ? 0 : (progress * 100).round()}% 完成',
                        style: const TextStyle(
                          fontSize: 9,
                          color: ShanganColors.mutedInk,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 15),
                  Row(
                    children: [
                      PadProgressDial(
                        progress: progress,
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.baseline,
                          textBaseline: TextBaseline.alphabetic,
                          children: [
                            Text(
                              '$done',
                              style: const TextStyle(
                                fontSize: 25,
                                height: 1,
                                fontWeight: FontWeight.w700,
                                letterSpacing: -1,
                                fontFeatures: [FontFeature.tabularFigures()],
                              ),
                            ),
                            Text(
                              '/$total',
                              style: const TextStyle(
                                fontSize: 10,
                                color: ShanganColors.mutedInk,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              copy.$1,
                              style: const TextStyle(
                                fontSize: 13,
                                height: 1.65,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              copy.$2,
                              style: const TextStyle(
                                fontSize: 9,
                                height: 1.9,
                                color: ShanganColors.mutedInk,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  for (final type in TodoType.values)
                    _RhythmRow(
                      icon: switch (type) {
                        TodoType.course => Icons.menu_book_outlined,
                        TodoType.focus => Icons.timer_outlined,
                        TodoType.task => Icons.task_alt_outlined,
                      },
                      label: switch (type) {
                        TodoType.course => '课程学习',
                        TodoType.focus => '专注练习',
                        TodoType.task => '日常待办',
                      },
                      done: view.todos
                          .where((todo) => todo.todoType == type && todo.isDone)
                          .length,
                      total: view.todos
                          .where((todo) => todo.todoType == type)
                          .length,
                    ),
                  const SizedBox(height: 8),
                  const Divider(height: 1, color: ShanganColors.hair),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          '把坚持变成看得见的进步',
                          style: TextStyle(
                            fontSize: 9,
                            color: ShanganColors.mutedInk,
                          ),
                        ),
                      ),
                      GestureDetector(
                        onTap: onOpenStats,
                        child: const Text(
                          '查看数据 ›',
                          style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.w600,
                            color: ShanganColors.blue,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            );
          },
          orElse: () => const SizedBox.shrink(),
        );
  }
}

final class _RhythmRow extends StatelessWidget {
  const _RhythmRow({
    required this.icon,
    required this.label,
    required this.done,
    required this.total,
  });

  final IconData icon;
  final String label;
  final int done;
  final int total;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(icon, size: 14, color: ShanganColors.mutedInk),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              label,
              style: const TextStyle(
                fontSize: 10,
                color: ShanganColors.mutedInk,
              ),
            ),
          ),
          Text.rich(
            TextSpan(
              text: '$done ',
              style: const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: ShanganColors.ink,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
              children: [
                TextSpan(
                  text: '/ $total',
                  style: const TextStyle(
                    fontWeight: FontWeight.w500,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Pad 首页红色「添加待办」，替代手机右下角 FAB。
final class PadAddTodoButton extends StatelessWidget {
  const PadAddTodoButton({required this.onTap, super.key});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: '添加待办',
      child: FilledButton.icon(
        onPressed: onTap,
        style: FilledButton.styleFrom(
          backgroundColor: ShanganColors.red,
          foregroundColor: Colors.white,
          minimumSize: const Size(112, 42),
          padding: const EdgeInsets.symmetric(horizontal: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
        ),
        icon: const Icon(Icons.add, size: 16),
        label: const Text('添加待办'),
      ),
    );
  }
}
