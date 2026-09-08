import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/home/presentation/add_todo_sheet.dart';
import 'package:shangan_ios/features/home/presentation/complete_sheet.dart';
import 'package:shangan_ios/features/home/presentation/delete_reason_dialog.dart';
import 'package:shangan_ios/features/home/presentation/todo_row.dart';
import 'package:shangan_ios/features/nag/presentation/fullscreen_nag_page.dart';

/// 首页：目标看板 + 今日指标 + 分组 Todo 列表，支持日 / 周 / 月视图与历史日期。
///
/// 对应原型 1-1 ~ 1-10：默认态、编辑态、视图切换面板、周 / 月视图、
/// 历史日补救动作与回顾态。
final class HomePage extends ConsumerStatefulWidget {
  const HomePage({super.key});

  @override
  ConsumerState<HomePage> createState() => HomePageState();
}

class HomePageState extends ConsumerState<HomePage> {
  bool _editing = false;

  /// 日期头展开的视图切换面板（原型 1-4）。
  bool _jumpPanelOpen = false;
  final _selected = <String>{};

  /// 供外层 FAB 调用。
  Future<void> addTodo() async {
    try {
      final selection = ref.read(homeSelectionProvider);
      final date = selection.followsToday
          ? (await ref.read(shanganRepositoryProvider).loadDay()).date
          : selection.date;
      if (!mounted) return;
      final created = await AddTodoSheet.show(context, date);
      if (created) await _refresh();
    } catch (_) {
      if (mounted) ShanganFeedback.show(context, '读取今日日期失败，请稍后重试', error: true);
    }
  }

  Future<void> _refresh() async {
    ref.invalidate(serverTodayProvider);
    ref.invalidate(dayViewProvider);
    ref.invalidate(weekViewProvider);
    ref.invalidate(monthViewProvider);
    ref.invalidate(goalsProvider);
    ref.invalidate(pendingSummaryProvider);
    ref.invalidate(pendingNagProvider);
  }

  @override
  Widget build(BuildContext context) {
    final selection = ref.watch(homeSelectionProvider);
    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(18, 6, 18, 110),
        children: [
          _Header(
            selection: selection,
            editing: _editing,
            selectedCount: _selected.length,
            jumpPanelOpen: _jumpPanelOpen,
            onToggleEditing: () => setState(() {
              _editing = !_editing;
              _jumpPanelOpen = false;
              _selected.clear();
            }),
            onToggleJumpPanel: () =>
                setState(() => _jumpPanelOpen = !_jumpPanelOpen),
          ),
          const SizedBox(height: 12),
          if (!_editing) const _PendingNagStrip(),
          if (_jumpPanelOpen && !_editing) ...[
            _JumpPanel(
              selection: selection,
              onClose: () => setState(() => _jumpPanelOpen = false),
            ),
            const SizedBox(height: 12),
          ],
          if (!_editing) ...[
            _GoalBoardSection(onManage: () => context.push('/goals')),
            const SizedBox(height: 12),
          ],
          // 原型 1-1 默认日视图不显示分段控件，切换入口在日期头面板里；
          // 周 / 月视图（1-5、1-6）才把分段常驻在页头下方。
          if (!_editing && selection.range != HomeRange.day) ...[
            ShanganSegmented(
              labels: const ['日', '周', '月'],
              selectedIndex: selection.range.index,
              onChanged: (index) => ref
                  .read(homeSelectionProvider.notifier)
                  .selectRange(HomeRange.values[index]),
            ),
            const SizedBox(height: 12),
          ],
          if (_editing)
            _EditingSection(
              selected: _selected,
              onSelectionChanged: (id, value) => setState(() {
                if (value) {
                  _selected.add(id);
                } else {
                  _selected.remove(id);
                }
              }),
              onDone: () async {
                setState(() {
                  _editing = false;
                  _selected.clear();
                });
                await _refresh();
              },
            )
          else
            switch (selection.range) {
              HomeRange.day => _DaySection(
                onRefresh: _refresh,
                onEdit: () => setState(() {
                  _editing = true;
                  _jumpPanelOpen = false;
                  _selected.clear();
                }),
              ),
              HomeRange.week => const _WeekSection(),
              HomeRange.month => _MonthSection(onRefresh: _refresh),
            },
        ],
      ),
    );
  }
}

/// 页头：kicker + 大号日期 + 右侧日历 / 铃铛按钮。
///
/// 编辑态换成「编辑今日 / 已选 N 项 + 完成」（原型 1-2）。
final class _Header extends ConsumerWidget {
  const _Header({
    required this.selection,
    required this.editing,
    required this.selectedCount,
    required this.jumpPanelOpen,
    required this.onToggleEditing,
    required this.onToggleJumpPanel,
  });

  final HomeSelection selection;
  final bool editing;
  final int selectedCount;
  final bool jumpPanelOpen;
  final VoidCallback onToggleEditing;
  final VoidCallback onToggleJumpPanel;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (editing) {
      return Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '编辑今日',
                  style: TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.6,
                    color: ShanganColors.mutedInk,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  '已选 $selectedCount 项',
                  style: const TextStyle(
                    fontSize: 26,
                    height: 1.1,
                    fontWeight: FontWeight.w800,
                    letterSpacing: -1,
                  ),
                ),
              ],
            ),
          ),
          ShanganFilterChip(
            label: '完成',
            selected: true,
            tone: ShanganBadgeTone.blue,
            onTap: onToggleEditing,
          ),
        ],
      );
    }
    final date = selection.date;
    final today =
        ref.watch(serverTodayProvider).asData?.value ?? selection.date;
    final isToday = date.isAtSameMomentAs(today);
    final overdueDays = today.difference(date).inDays;
    final (
      String kicker,
      String title,
      double titleSize,
    ) = switch (selection.range) {
      HomeRange.week => ('WEEK · ${_weekLabel(date)}', '本周待办', 26.0),
      HomeRange.month => ('MONTH · ${date.year}', '${date.month} 月', 26.0),
      HomeRange.day =>
        isToday
            ? ('TODAY · 周${weekdayLabel(date)}', formatDate(date), 31.0)
            : (
                overdueDays > 0 ? '历史 · $overdueDays 天前' : '未来 · 计划中',
                '${formatDate(date)} 周${weekdayLabel(date)}',
                26.0,
              ),
    };
    final nag = ref.watch(pendingNagProvider);
    final hasNag = nag.maybeWhen(
      data: (value) => value != null,
      orElse: () => false,
    );
    return Row(
      children: [
        Expanded(
          child: GestureDetector(
            onTap: onToggleJumpPanel,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  kicker,
                  style: const TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.6,
                    color: ShanganColors.mutedInk,
                  ),
                ),
                const SizedBox(height: 2),
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: titleSize,
                          height: 1.1,
                          fontWeight: FontWeight.w800,
                          letterSpacing: -1,
                        ),
                      ),
                    ),
                    const SizedBox(width: 7),
                    RotatedBox(
                      quarterTurns: jumpPanelOpen ? 3 : 1,
                      child: const Icon(
                        Icons.chevron_right,
                        size: 20,
                        color: ShanganColors.blue,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        ShanganIconButton(
          icon: Icons.calendar_today_outlined,
          highlighted: jumpPanelOpen,
          semanticLabel: '切换视图与日期',
          onTap: onToggleJumpPanel,
        ),
        const SizedBox(width: 9),
        ShanganIconButton(
          icon: Icons.notifications_none,
          showDot: hasNag,
          semanticLabel: hasNag ? '有待回应催办' : '暂无待回应催办',
          onTap: () => _openNag(context, ref, hasNag),
        ),
      ],
    );
  }

  String _weekLabel(DateTime date) {
    final monday = date.subtract(Duration(days: date.weekday - 1));
    final sunday = monday.add(const Duration(days: 6));
    return '${monday.month}/${monday.day} – ${sunday.month}/${sunday.day}';
  }

  Future<void> _openNag(
    BuildContext context,
    WidgetRef ref,
    bool hasNag,
  ) async {
    if (!hasNag) {
      ShanganFeedback.show(context, '当前没有待回应的催办');
      return;
    }
    final nag = ref.read(pendingNagProvider).value;
    if (nag == null) return;
    final settings = ref.read(meSettingsProvider).value;
    await FullscreenNagPage.show(
      context,
      nag: nag,
      minReasonLength: settings?.minReasonLength ?? 5,
    );
    ref.invalidate(pendingNagProvider);
    ref.invalidate(dayViewProvider);
  }
}

/// 原型 7-2 的红色待回应条；点击重新拉起全屏催办。
final class _PendingNagStrip extends ConsumerWidget {
  const _PendingNagStrip();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final nag = ref.watch(pendingNagProvider).value;
    if (nag == null) return const SizedBox.shrink();
    final settings = ref.watch(meSettingsProvider).value;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: GestureDetector(
        onTap: () async {
          await FullscreenNagPage.show(
            context,
            nag: nag,
            minReasonLength: settings?.minReasonLength ?? 5,
          );
          ref.invalidate(pendingNagProvider);
          ref.invalidate(dayViewProvider);
        },
        child: ShanganCard(
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
          borderColor: ShanganColors.red,
          backgroundColor: ShanganColors.redSoft,
          child: Row(
            children: [
              const Icon(
                Icons.error_outline,
                size: 18,
                color: ShanganColors.red,
              ),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  '有 1 条催办待回应',
                  style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                    color: ShanganColors.red,
                  ),
                ),
              ),
              const ShanganBadge(label: '立即处理', tone: ShanganBadgeTone.red),
            ],
          ),
        ),
      ),
    );
  }
}

/// 原型 1-4 的视图切换与日期跳转面板；最近 7 天数据取自月视图摘要。
final class _JumpPanel extends ConsumerWidget {
  const _JumpPanel({required this.selection, required this.onClose});

  final HomeSelection selection;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final month = ref.watch(monthViewProvider);
    final byDate = <DateTime, DaySummary>{
      for (final day in month.value?.days ?? const <DaySummary>[])
        DateTime(day.date.year, day.date.month, day.date.day): day,
    };
    final recent = <DaySummary>[
      for (var offset = 6; offset >= 0; offset--)
        _summaryFor(
          byDate,
          selection.date.subtract(Duration(days: offset)),
          ref.watch(serverTodayProvider).asData?.value,
        ),
    ];
    return ShanganDateJumpPanel(
      rangeIndex: selection.range.index,
      onRangeChanged: (index) {
        ref
            .read(homeSelectionProvider.notifier)
            .selectRange(HomeRange.values[index]);
        if (index != 0) onClose();
      },
      selectedDate: selection.date,
      onSelectDate: (date) {
        ref.read(homeSelectionProvider.notifier).selectDate(date);
        onClose();
      },
      recentDays: recent,
    );
  }

  /// 月视图没覆盖到的日期（跨月边界）按「无待办」渲染，避免额外请求。
  DaySummary _summaryFor(
    Map<DateTime, DaySummary> byDate,
    DateTime date,
    DateTime? today,
  ) {
    final key = DateTime(date.year, date.month, date.day);
    final existing = byDate[key];
    if (existing != null) return existing;
    return DaySummary(
      date: key,
      today: today != null && key.isAtSameMomentAs(today),
      total: 0,
      done: 0,
      pending: 0,
      watchedMs: 0,
      focusedMs: 0,
      countByType: const {},
      outcome: DayOutcome.noTodos,
    );
  }
}

final class _GoalBoardSection extends ConsumerWidget {
  const _GoalBoardSection({required this.onManage});

  final VoidCallback onManage;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final goals = ref.watch(goalsProvider);
    return goals.when(
      loading: () => const SizedBox(
        height: 96,
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (error, _) => ShanganCard(
        padding: const EdgeInsets.all(14),
        child: Text('目标加载失败：$error'),
      ),
      data: (list) => GoalBoard(goals: list, onManage: onManage),
    );
  }
}

/// 日视图：四格指标 + 历史提示条 + 三组 Todo 列表。
final class _DaySection extends ConsumerStatefulWidget {
  const _DaySection({required this.onRefresh, this.onEdit});

  final Future<void> Function() onRefresh;

  /// 进入编辑态（原型 1-1「进行中」标题右侧的「编辑」）；历史日不提供。
  final VoidCallback? onEdit;

  @override
  ConsumerState<_DaySection> createState() => _DaySectionState();
}

class _DaySectionState extends ConsumerState<_DaySection> {
  /// 原型 1-1「已完成」分组标题右侧的「收起」。
  bool _doneCollapsed = false;

  @override
  Widget build(BuildContext context) {
    final day = ref.watch(dayViewProvider);
    final settings = ref.watch(meSettingsProvider);
    final minReasonLength = settings.maybeWhen(
      data: (value) => value.minReasonLength,
      orElse: () => 5,
    );
    final supervisorName = settings.maybeWhen(
      data: (value) => value.supervisors.isEmpty
          ? null
          : value.supervisors.first.displayName,
      orElse: () => null,
    );
    return day.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (error, _) => ShanganCard(
        padding: const EdgeInsets.all(14),
        child: Text('今日待办加载失败：$error'),
      ),
      data: (view) {
        final pending = view.todos
            .where((todo) => !todo.isDone)
            .toList(growable: false);
        final allDone = view.totals.total > 0 && pending.isEmpty;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            StatStrip(
              items: [
                StatStripItem(
                  value: '${view.totals.done}/${view.totals.total}',
                  label: view.history ? '当日完成' : '今日完成',
                  highlighted: true,
                ),
                StatStripItem(
                  value: formatDurationCompact(view.totals.watchedMs),
                  label: view.history ? '观看' : '观看时长',
                ),
                StatStripItem(
                  value: formatDurationCompact(view.totals.focusedMs),
                  label: view.history ? '专注' : '专注时长',
                ),
                if (view.history)
                  StatStripItem(value: '${pending.length}', label: '未完成')
                else
                  StatStripItem(
                    value: '${view.totals.attachmentCount}',
                    label: '附件',
                  ),
              ],
            ),
            if (view.history) ...[
              const SizedBox(height: 12),
              if (allDone)
                // 原型 1-9：全部完成的历史日切换成绿色回顾条。
                ShanganCard(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 13,
                    vertical: 11,
                  ),
                  borderColor: ShanganColors.greenLine,
                  backgroundColor: ShanganColors.greenSoft,
                  child: Row(
                    children: [
                      const Icon(
                        Icons.check,
                        size: 18,
                        color: ShanganColors.green,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '当日 ${view.totals.done} / ${view.totals.total} 全部完成 · '
                          '${formatDurationCompact(view.totals.watchedMs + view.totals.focusedMs)}',
                          style: const TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w700,
                            color: ShanganColors.green,
                          ),
                        ),
                      ),
                    ],
                  ),
                )
              else
                const ShanganCard(
                  padding: EdgeInsets.symmetric(horizontal: 13, vertical: 11),
                  borderColor: ShanganColors.ochreLine,
                  backgroundColor: ShanganColors.ochreSoft,
                  child: Row(
                    children: [
                      Icon(
                        Icons.access_time,
                        size: 18,
                        color: ShanganColors.ochre,
                      ),
                      SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '历史日期 · 只能补记或顺延',
                          style: TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w700,
                            color: shanganTipText,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
            ],
            if (view.deletionCount > 0) ...[
              const SizedBox(height: 10),
              Text(
                '当日删除 ${view.deletionCount} 项，已记入台账',
                style: const TextStyle(
                  fontSize: 11.5,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
            if (view.history && allDone)
              // 1-9 回顾态：按完成时刻排成当日时间轴，只读。
              _group(
                title: '当日时间轴',
                items: view.completed,
                view: view,
                minReasonLength: minReasonLength,
                supervisorName: supervisorName,
                showCount: false,
                readOnly: true,
                showCompletedTime: true,
              )
            else ...[
              _group(
                title: '进行中',
                items: view.inProgress,
                view: view,
                minReasonLength: minReasonLength,
                supervisorName: supervisorName,
                // 编辑入口挂在第一个非空的未完成分组上，保证任何一天都能进入编辑态。
                showEdit: view.inProgress.isNotEmpty,
              ),
              _group(
                title: view.history ? '未完成' : '待开始',
                items: view.notStarted,
                view: view,
                minReasonLength: minReasonLength,
                supervisorName: supervisorName,
                borderColor: view.history ? ShanganColors.redLine : null,
                showEdit: view.inProgress.isEmpty,
              ),
              _group(
                title: '已完成',
                items: view.completed,
                view: view,
                minReasonLength: minReasonLength,
                supervisorName: supervisorName,
                collapsible: true,
                readOnly: view.history,
              ),
            ],
            if (view.todos.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 32),
                child: Column(
                  children: [
                    const Icon(
                      Icons.checklist_rtl,
                      size: 40,
                      color: ShanganColors.rule,
                    ),
                    const SizedBox(height: 10),
                    Text(
                      view.today ? '今天还没有安排，点右下角加一条' : '这一天没有待办记录',
                      style: const TextStyle(color: ShanganColors.mutedInk),
                    ),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }

  Widget _group({
    required String title,
    required List<TodoItem> items,
    required DayView view,
    required int minReasonLength,
    String? supervisorName,
    bool showCount = true,
    bool collapsible = false,
    bool readOnly = false,
    bool showCompletedTime = false,
    bool showEdit = false,
    Color? borderColor,
  }) {
    if (items.isEmpty) return const SizedBox.shrink();
    final collapsed = collapsible && _doneCollapsed;
    // 历史日只能补记 / 顺延 / 删除单项，不进入批量编辑态。
    final editAction = showEdit && !view.history ? widget.onEdit : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionTitle(
          title: title,
          count: showCount ? '${items.length}' : null,
          trailing: collapsible
              ? TextButton(
                  onPressed: () =>
                      setState(() => _doneCollapsed = !_doneCollapsed),
                  child: Text(collapsed ? '展开' : '收起'),
                )
              : editAction == null
              ? null
              : TextButton(onPressed: editAction, child: const Text('编辑')),
        ),
        if (!collapsed)
          ShanganCard(
            borderColor: borderColor,
            child: Column(
              children: [
                for (var index = 0; index < items.length; index++) ...[
                  if (index > 0)
                    const Divider(height: 1, color: ShanganColors.hair),
                  _HistoryAwareRow(
                    todo: items[index],
                    history: view.history,
                    minReasonLength: minReasonLength,
                    supervisorName: supervisorName,
                    readOnly: readOnly,
                    showCompletedTime: showCompletedTime,
                    onChanged: widget.onRefresh,
                  ),
                ],
              ],
            ),
          ),
      ],
    );
  }
}

/// 历史日期的未完成项额外提供顺延、补记与删除三个动作（原型 1-7）。
final class _HistoryAwareRow extends ConsumerWidget {
  const _HistoryAwareRow({
    required this.todo,
    required this.history,
    required this.minReasonLength,
    required this.onChanged,
    this.supervisorName,
    this.readOnly = false,
    this.showCompletedTime = false,
  });

  final TodoItem todo;
  final bool history;
  final int minReasonLength;
  final Future<void> Function() onChanged;
  final String? supervisorName;
  final bool readOnly;
  final bool showCompletedTime;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final overdue = history && !todo.isDone;
    final row = TodoRow(
      todo: todo,
      minReasonLength: minReasonLength,
      supervisorName: supervisorName,
      overdue: overdue,
      readOnly: readOnly || overdue,
      showCompletedTime: showCompletedTime,
      onChanged: onChanged,
    );
    if (!overdue) return row;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        row,
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Wrap(
            spacing: 7,
            runSpacing: 7,
            children: [
              if (todo.todoType != TodoType.course || todo.resourceAvailable)
                ShanganFilterChip(
                  label: '顺延今天',
                  selected: false,
                  onTap: () async {
                    final today =
                        (await ref.read(shanganRepositoryProvider).loadDay())
                            .date;
                    await ref
                        .read(shanganRepositoryProvider)
                        .deferTodo(todo.id, today);
                    await onChanged();
                  },
                ),
              ShanganFilterChip(
                label: '补记完成',
                selected: false,
                onTap: () async {
                  final done = await CompleteSheet.show(
                    context,
                    todo: todo,
                    backfill: true,
                  );
                  if (done) await onChanged();
                },
              ),
              ShanganFilterChip(
                label: '删除',
                selected: false,
                tone: ShanganBadgeTone.red,
                onTap: () async {
                  final deleted = await DeleteReasonDialog.show(
                    context,
                    todos: [todo],
                    minReasonLength: minReasonLength,
                    supervisorName: supervisorName,
                  );
                  if (deleted) await onChanged();
                },
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// 周视图：四格指标 + 按天折叠摘要 + 本周未完成汇总入口（原型 1-5）。
final class _WeekSection extends ConsumerWidget {
  const _WeekSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final range = ref.watch(weekViewProvider);
    return range.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (error, _) => ShanganCard(
        padding: const EdgeInsets.all(14),
        child: Text('聚合视图加载失败：$error'),
      ),
      data: (view) {
        final effectiveDays = view.days.where((day) => day.total > 0).length;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            StatStrip(
              items: [
                StatStripItem(
                  value: '${view.totals.done}/${view.totals.total}',
                  label: '本周完成',
                  highlighted: true,
                ),
                StatStripItem(
                  value: formatDurationCompact(
                    view.totals.watchedMs + view.totals.focusedMs,
                  ),
                  label: '总时长',
                ),
                StatStripItem(value: '${view.totals.pending}', label: '未完成'),
                StatStripItem(value: '$effectiveDays', label: '有效天'),
              ],
            ),
            const SizedBox(height: 12),
            ShanganCard(
              child: Column(
                children: [
                  for (var index = 0; index < view.days.length; index++) ...[
                    if (index > 0)
                      const Divider(height: 1, color: ShanganColors.hair),
                    _DaySummaryRow(summary: view.days[index]),
                  ],
                ],
              ),
            ),
            if (view.totals.pending > 0) ...[
              const SizedBox(height: 12),
              GestureDetector(
                onTap: () => context.push('/todos/pending'),
                child: ShanganCard(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 12,
                  ),
                  borderColor: ShanganColors.redLine,
                  backgroundColor: ShanganColors.redSoft,
                  child: Row(
                    children: [
                      const Icon(
                        Icons.error_outline,
                        size: 18,
                        color: ShanganColors.red,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '本周 ${view.totals.pending} 项未完成',
                          style: const TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w700,
                            color: ShanganColors.red,
                          ),
                        ),
                      ),
                      const ShanganBadge(
                        label: '查看汇总',
                        tone: ShanganBadgeTone.red,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
        );
      },
    );
  }
}

/// 月视图：月历格 + 选中日当天 Todo 列表（原型 1-6）。
final class _MonthSection extends ConsumerWidget {
  const _MonthSection({required this.onRefresh});

  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selection = ref.watch(homeSelectionProvider);
    final range = ref.watch(monthViewProvider);
    final day = ref.watch(dayViewProvider);
    final settings = ref.watch(meSettingsProvider);
    final minReasonLength = settings.maybeWhen(
      data: (value) => value.minReasonLength,
      orElse: () => 5,
    );
    return range.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (error, _) => ShanganCard(
        padding: const EdgeInsets.all(14),
        child: Text('聚合视图加载失败：$error'),
      ),
      data: (view) {
        final selectedSummary = view.days.firstWhere(
          (item) => item.date.isAtSameMomentAs(selection.date),
          orElse: () => view.days.isEmpty
              ? DaySummary(
                  date: selection.date,
                  today: false,
                  total: 0,
                  done: 0,
                  pending: 0,
                  watchedMs: 0,
                  focusedMs: 0,
                  countByType: const {},
                  outcome: DayOutcome.noTodos,
                )
              : view.days.first,
        );
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            ShanganCard(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
              child: ShanganMonthCalendar(
                month: selection.date,
                days: view.days,
                selectedDate: selection.date,
                onSelect: (date) => ref
                    .read(homeSelectionProvider.notifier)
                    .selectDateKeepingRange(date),
              ),
            ),
            SectionTitle(
              title:
                  '${formatDate(selection.date)} · 周${weekdayLabel(selection.date)}',
              count: '${selectedSummary.done} / ${selectedSummary.total}',
              trailing: TextButton(
                onPressed: () => ref
                    .read(homeSelectionProvider.notifier)
                    .selectDate(selection.date),
                child: const Text('日视图'),
              ),
            ),
            day.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (error, _) => ShanganCard(
                padding: const EdgeInsets.all(14),
                child: Text('当日待办加载失败：$error'),
              ),
              data: (dayView) => dayView.todos.isEmpty
                  ? const Padding(
                      padding: EdgeInsets.symmetric(vertical: 24),
                      child: Text(
                        '这一天没有待办记录',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: ShanganColors.mutedInk),
                      ),
                    )
                  : ShanganCard(
                      child: Column(
                        children: [
                          for (
                            var index = 0;
                            index < dayView.todos.length;
                            index++
                          ) ...[
                            if (index > 0)
                              const Divider(
                                height: 1,
                                color: ShanganColors.hair,
                              ),
                            TodoRow(
                              todo: dayView.todos[index],
                              compact: true,
                              minReasonLength: minReasonLength,
                              onChanged: onRefresh,
                            ),
                          ],
                        ],
                      ),
                    ),
            ),
          ],
        );
      },
    );
  }
}

/// 周视图内的按天摘要行（原型 1-5）。
final class _DaySummaryRow extends ConsumerWidget {
  const _DaySummaryRow({required this.summary});

  final DaySummary summary;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final outcome = dayOutcomeStyle(summary.outcome);
    return InkWell(
      onTap: summary.total == 0
          ? null
          : () => ref
                .read(homeSelectionProvider.notifier)
                .selectDate(summary.date),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 13),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 38,
              child: Column(
                children: [
                  Text(
                    summary.today ? '今天' : weekdayLabel(summary.date),
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      color: summary.today
                          ? ShanganColors.blue
                          : ShanganColors.mutedInk,
                    ),
                  ),
                  Text(
                    '${summary.date.day}',
                    style: TextStyle(
                      fontSize: 17,
                      fontWeight: FontWeight.w800,
                      color: summary.today
                          ? ShanganColors.blue
                          : ShanganColors.ink,
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '周${weekdayLabel(summary.date)} · '
                    '${summary.done} / ${summary.total} 完成',
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 5),
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: _badges(),
                  ),
                  const SizedBox(height: 6),
                  TargetProgressBar(
                    value: summary.total == 0
                        ? 0
                        : summary.done / summary.total,
                    color: outcome.color,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${formatDurationCompact(summary.watchedMs + summary.focusedMs)}'
                    '${summary.pending > 0 ? ' · ${summary.pending} 项未完成' : ''}',
                    style: const TextStyle(
                      fontSize: 11,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            ShanganActButton(
              icon: Icons.chevron_right,
              tone: ShanganActTone.flat,
              // 当天默认展开，箭头朝下，与原型 1-5 首行一致。
              quarterTurns: summary.today ? 1 : 0,
              semanticLabel: '查看 ${summary.date.day} 日明细',
              onTap: summary.total == 0
                  ? null
                  : () => ref
                        .read(homeSelectionProvider.notifier)
                        .selectDate(summary.date),
            ),
          ],
        ),
      ),
    );
  }

  /// 今天显示类型计数，全部完成 / 零完成显示结果徽标。
  List<Widget> _badges() {
    if (summary.today) {
      return [
        for (final type in TodoType.values)
          if ((summary.countByType[type] ?? 0) > 0)
            ShanganBadge(
              label: '${type.label} ${summary.countByType[type]}',
              tone: switch (type) {
                TodoType.course => ShanganBadgeTone.blue,
                TodoType.focus => ShanganBadgeTone.ochre,
                TodoType.task => ShanganBadgeTone.green,
              },
            ),
      ];
    }
    return switch (summary.outcome) {
      DayOutcome.allDone => const [
        ShanganBadge(label: '全部完成', tone: ShanganBadgeTone.green),
      ],
      DayOutcome.noneDone => const [
        ShanganBadge(label: '零完成', tone: ShanganBadgeTone.red),
      ],
      DayOutcome.noTodos => const [
        ShanganBadge(label: '无待办', tone: ShanganBadgeTone.ink),
      ],
      DayOutcome.partial => const [],
    };
  }
}

/// 编辑态：提示条 + 可拖拽排序的 pick 列表 + 批量顺延 / 删除（原型 1-2）。
final class _EditingSection extends ConsumerWidget {
  const _EditingSection({
    required this.selected,
    required this.onSelectionChanged,
    required this.onDone,
  });

  final Set<String> selected;
  final void Function(String id, bool value) onSelectionChanged;
  final Future<void> Function() onDone;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final day = ref.watch(dayViewProvider);
    final settings = ref.watch(meSettingsProvider);
    final minReasonLength = settings.maybeWhen(
      data: (value) => value.minReasonLength,
      orElse: () => 5,
    );
    return day.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (error, _) => ShanganCard(
        padding: const EdgeInsets.all(14),
        child: Text('今日待办加载失败：$error'),
      ),
      data: (view) {
        final todos = [...view.todos]
          ..sort((a, b) => a.sortOrder.compareTo(b.sortOrder));
        final picked = todos
            .where((todo) => selected.contains(todo.id))
            .toList(growable: false);
        // 下架课时仍可勾选删除，但不参与顺延。
        final movablePicked = picked
            .where(
              (todo) =>
                  todo.todoType != TodoType.course || todo.resourceAvailable,
            )
            .toList(growable: false);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const ShanganTip(
              text: '删除任意待办（含批量）都必须填写删除说明，会记入删除台账并同步给督学人，用于统计与提醒。',
            ),
            const SizedBox(height: 12),
            ShanganCard(
              child: ReorderableListView.builder(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                buildDefaultDragHandles: false,
                itemCount: todos.length,
                onReorderItem: (oldIndex, newIndex) async {
                  final reordered = [...todos];
                  final moving = reordered.removeAt(oldIndex);
                  reordered.insert(newIndex, moving);
                  await ref
                      .read(shanganRepositoryProvider)
                      .reorder(
                        view.date,
                        reordered
                            .map((todo) => todo.id)
                            .toList(growable: false),
                      );
                  ref.invalidate(dayViewProvider);
                },
                itemBuilder: (context, index) {
                  final todo = todos[index];
                  return Column(
                    key: ValueKey(todo.id),
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (index > 0)
                        const Divider(height: 1, color: ShanganColors.hair),
                      ShanganPickRow(
                        title: todo.title,
                        subtitle: _describe(todo),
                        checked: selected.contains(todo.id),
                        onToggle: (value) => onSelectionChanged(todo.id, value),
                        trailing: ReorderableDragStartListener(
                          index: index,
                          child: const Icon(
                            Icons.drag_handle,
                            size: 20,
                            color: ShanganColors.mutedInk,
                          ),
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: movablePicked.isEmpty
                        ? null
                        : () async {
                            final today =
                                (await ref
                                        .read(shanganRepositoryProvider)
                                        .loadDay())
                                    .date;
                            final tomorrow = DateTime(
                              today.year,
                              today.month,
                              today.day + 1,
                            );
                            await ref
                                .read(shanganRepositoryProvider)
                                .deferAll(
                                  movablePicked
                                      .map((todo) => todo.id)
                                      .toList(growable: false),
                                  DateTime(
                                    tomorrow.year,
                                    tomorrow.month,
                                    tomorrow.day,
                                  ),
                                );
                            await onDone();
                          },
                    icon: const Icon(Icons.event_outlined, size: 18),
                    label: const Text('移到明天'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      foregroundColor: ShanganColors.red,
                      side: const BorderSide(
                        color: ShanganColors.red,
                        width: 1.5,
                      ),
                    ),
                    onPressed: picked.isEmpty
                        ? null
                        : () async {
                            final deleted = await DeleteReasonDialog.show(
                              context,
                              todos: picked,
                              minReasonLength: minReasonLength,
                            );
                            if (deleted) await onDone();
                          },
                    icon: const Icon(Icons.delete_outline, size: 18),
                    label: Text('删除 ${picked.length} 项'),
                  ),
                ),
              ],
            ),
          ],
        );
      },
    );
  }

  /// pick 行副标题：类型 + 状态 + 进度，对应原型「课程 · 进行中 44%」。
  String _describe(TodoItem todo) {
    final status = switch (todo.status) {
      TodoStatus.todo => '未开始',
      TodoStatus.inProgress => '进行中',
      TodoStatus.done => '已完成',
    };
    return switch (todo.todoType) {
      TodoType.course =>
        '${todo.todoType.label} · $status'
            '${todo.status == TodoStatus.inProgress ? ' ${todo.progressPermille ~/ 10}%' : ''}',
      TodoType.focus =>
        '${todo.todoType.label} · '
            '${todo.focusState == FocusState.running ? '运行中' : status}',
      TodoType.task => '${todo.todoType.label} · $status',
    };
  }
}
