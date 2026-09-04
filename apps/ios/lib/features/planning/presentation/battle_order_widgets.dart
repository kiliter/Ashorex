import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_markdown.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

/// 首页和学习日历共用的作战单任务列表展示，不负责保存。
final class BattleOrderDayPanel extends StatelessWidget {
  const BattleOrderDayPanel({
    required this.plan,
    required this.grouped,
    required this.readOnly,
    required this.showDebtMarks,
    super.key,
    this.eyebrow,
    this.onEdit,
    this.continueLabel,
    this.onOpenItem,
    this.resumeQueue = false,
  });

  final DailyPlanData plan;
  final bool grouped;
  final bool readOnly;
  final bool showDebtMarks;

  /// 首页接续队列：列出未完成欠债和今日任务，按最近学习排在前面。
  final bool resumeQueue;
  final String? eyebrow;
  final VoidCallback? onEdit;
  final String? continueLabel;
  final ValueChanged<PlanItemData>? onOpenItem;

  void _open(BuildContext context, PlanItemData item) {
    if (onOpenItem != null) {
      onOpenItem!(item);
      return;
    }
    openBattleOrderItem(context, item);
  }

  @override
  Widget build(BuildContext context) {
    final items = plan.items;
    final listItems = resumeQueue ? homeResumeItems(items) : items;
    final debtCount = items.where(_isDebtItem).length;
    final progress = battleOrderProgress(items);
    final headline = items.isEmpty
        ? '今天还没有作战单'
        : '${items.length} 项任务${debtCount > 0 ? '，含 $debtCount 项欠债' : ''}';
    return ShanganSurface(
      borderColor: ShanganColors.blue,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: ShanganEyebrow(
                  eyebrow ?? '作战单 · ${_planStatusLabel(plan.status)}',
                ),
              ),
              ShanganStatusTag(
                _planStatusLabel(plan.status),
                tone: _planTone(plan.status),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Text(
                  headline,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
              ),
              ShanganCountUpPercent(value: progress),
            ],
          ),
          const SizedBox(height: 12),
          ShanganProgress(value: progress, style: ShanganProgressStyle.track),
          const SizedBox(height: 8),
          if (items.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Text(
                readOnly ? '这一天没有保存过作战单。' : '集中选择今天要完成的课时或模拟考试。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            )
          else if (listItems.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Text(
                items.any(_isUnfinishedWork) ? '当前没有进行中的任务' : '未看完的任务已经全部完成。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            )
          else
            _BattleOrderScrollArea(
              itemCount: listItems.length,
              grouped: grouped,
              child: grouped
                  ? BattleOrderGroupedList(
                      items: listItems,
                      showDebtMarks: showDebtMarks,
                      onOpen: (item) => _open(context, item),
                    )
                  : Column(
                      children: [
                        for (final entry in listItems.indexed)
                          BattleOrderItemTile(
                            index: entry.$1,
                            item: entry.$2,
                            showDebtMark:
                                _isDebtItem(entry.$2) ||
                                (showDebtMarks && _isUnfinishedWork(entry.$2)),
                            onOpen: () => _open(context, entry.$2),
                          ),
                      ],
                    ),
            ),
          if (onEdit != null || continueLabel != null) ...[
            const SizedBox(height: 16),
            if (continueLabel != null)
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const Key('continueBattleOrder'),
                  onPressed: () {
                    final item = resumeQueue
                        ? firstResumableItem(items)
                        : firstActionableItem(items);
                    if (item != null) {
                      _open(context, item);
                    } else {
                      onEdit?.call();
                    }
                  },
                  child: Text(continueLabel!),
                ),
              ),
            if (onEdit != null) ...[
              if (continueLabel != null) const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  key: const Key('editBattleOrder'),
                  onPressed: onEdit,
                  icon: const Icon(Icons.edit_outlined),
                  label: Text(plan.status == 'NONE' ? '制定作战单' : '编辑作战单'),
                ),
              ),
            ],
          ],
        ],
      ),
    );
  }
}

/// 按课程折叠的作战单列表，组内仍保持课时固有顺序。
final class BattleOrderGroupedList extends StatelessWidget {
  const BattleOrderGroupedList({
    required this.items,
    required this.showDebtMarks,
    required this.onOpen,
    super.key,
  });

  final List<PlanItemData> items;
  final bool showDebtMarks;
  final ValueChanged<PlanItemData> onOpen;

  @override
  Widget build(BuildContext context) {
    final groups = groupBattleOrderItems(items);
    var index = 0;
    var courseNo = 0;
    return Column(
      children: [
        for (final entry in groups.indexed)
          Padding(
            padding: EdgeInsets.only(top: entry.$1 == 0 ? 4 : 12),
            child: BattleOrderCourseGroup(
              key: Key('course-group-${entry.$2.key}'),
              title: entry.$2.title,
              kindLabel: entry.$2.key == 'debt'
                  ? '欠债'
                  : entry.$2.key == 'mock-exam'
                  ? '模拟考试'
                  : '课程 ${++courseNo}',
              // 历史日把未完成课程名标红，进度行仍列出欠债；今日欠债组本身已是红色。
              debtAccent:
                  entry.$2.key == 'debt' ||
                  (showDebtMarks && entry.$2.items.any(_isUnfinishedWork)),
              itemCount: entry.$2.items.length,
              children: [
                for (final item in entry.$2.items)
                  BattleOrderItemTile(
                    index: index++,
                    item: item,
                    showDebtMark:
                        _isDebtItem(item) ||
                        (showDebtMarks && _isUnfinishedWork(item)),
                    onOpen: () => onOpen(item),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}

/// 课程分组展开控件，避免 ExpansionTile 在卡片背景上丢失水波纹。
final class BattleOrderCourseGroup extends StatefulWidget {
  const BattleOrderCourseGroup({
    required this.title,
    required this.children,
    super.key,
    this.kindLabel = '课程',
    this.itemCount,
    this.debtAccent = false,
    this.initiallyExpanded = true,
  });

  final String title;
  final String kindLabel;
  final int? itemCount;
  final bool debtAccent;
  final List<Widget> children;
  final bool initiallyExpanded;

  @override
  State<BattleOrderCourseGroup> createState() => _BattleOrderCourseGroupState();
}

final class _BattleOrderCourseGroupState extends State<BattleOrderCourseGroup> {
  late bool _expanded = widget.initiallyExpanded;

  @override
  Widget build(BuildContext context) {
    final accent = _courseTitleColor(
      kindLabel: widget.kindLabel,
      debtAccent: widget.debtAccent,
    );
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.transparent,
        border: Border.all(color: ShanganColors.rule, width: 1.5),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            onTap: () => setState(() => _expanded = !_expanded),
            borderRadius: const BorderRadius.vertical(top: Radius.circular(14)),
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 44),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
                child: Row(
                  children: [
                    Container(
                      width: 4,
                      height: 36,
                      decoration: BoxDecoration(
                        color: accent,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          ShanganEyebrow(
                            widget.itemCount == null
                                ? widget.kindLabel
                                : '${widget.kindLabel} · ${widget.itemCount} 项',
                          ),
                          const SizedBox(height: 2),
                          Text(
                            widget.title,
                            style: Theme.of(context).textTheme.titleMedium
                                ?.copyWith(
                                  color: accent,
                                  fontWeight: FontWeight.w700,
                                ),
                          ),
                        ],
                      ),
                    ),
                    Icon(
                      _expanded ? Icons.expand_less : Icons.expand_more,
                      color: ShanganColors.mutedInk,
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (_expanded)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
              child: Column(children: widget.children),
            ),
        ],
      ),
    );
  }
}

/// 单条作战任务：整行不跳转，右侧播放，视频课时另提供只读摘要。
final class BattleOrderItemTile extends ConsumerWidget {
  const BattleOrderItemTile({
    required this.index,
    required this.item,
    required this.showDebtMark,
    required this.onOpen,
    super.key,
  });

  final int index;
  final PlanItemData item;
  final bool showDebtMark;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = battleOrderItemStatus(item);
    final canPlay = battleOrderItemPlayable(item);
    final showSummary =
        item.mediaItemId != null &&
        (item.itemType == 'VIDEO' ||
            item.itemType == 'REVIEW_SHORTCUT' ||
            (item.itemType == 'DEBT_REPAYMENT' &&
                item.sourceDebtType != 'QUIZ' &&
                item.sourceDebtType != 'FOCUS'));
    return Container(
      constraints: const BoxConstraints(minHeight: 84),
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: ShanganColors.rule)),
      ),
      child: Row(
        children: [
          SizedBox(
            width: 28,
            child: Text(
              '${index + 1}'.padLeft(2, '0'),
              style: shanganNumberStyle(
                context,
                fontSize: 12,
              ).copyWith(color: ShanganColors.mutedInk),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  item.title,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 3),
                Text.rich(
                  TextSpan(
                    style: Theme.of(context).textTheme.bodySmall,
                    children: [
                      TextSpan(text: battleOrderItemSubtitle(item)),
                      TextSpan(
                        text: ' · ${status.label}',
                        style: TextStyle(
                          color: _statusTextColor(status.tone),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      if (showDebtMark)
                        const TextSpan(
                          text: ' · 欠债',
                          style: TextStyle(
                            color: ShanganColors.red,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                    ],
                  ),
                ),
                if (_showsWatchProgress(item)) ...[
                  const SizedBox(height: 6),
                  ShanganProgress(
                    value: item.plannedSeconds <= 0
                        ? 0
                        : (item.completedSeconds / item.plannedSeconds).clamp(
                            0,
                            1,
                          ),
                    style: ShanganProgressStyle.track,
                  ),
                ],
              ],
            ),
          ),
          if (showSummary)
            IconButton(
              key: Key('battleOrderSummary-${item.id}'),
              tooltip: '查看摘要',
              onPressed: () => showBattleOrderSummary(context, ref, item),
              icon: const Icon(Icons.visibility_outlined),
            ),
          IconButton(
            key: Key('battleOrderPlay-${item.id}'),
            tooltip:
                item.itemType == 'MOCK_EXAM' || item.sourceDebtType == 'QUIZ'
                ? '开始答题'
                : '播放',
            onPressed: canPlay ? onOpen : null,
            icon: Icon(
              item.itemType == 'MOCK_EXAM' || item.sourceDebtType == 'QUIZ'
                  ? Icons.assignment_outlined
                  : Icons.play_arrow_rounded,
            ),
          ),
        ],
      ),
    );
  }
}

/// 作战单只读弹出 AI 摘要；没有内容时用 SnackBar 说明，不进入播放页。
Future<void> showBattleOrderSummary(
  BuildContext context,
  WidgetRef ref,
  PlanItemData item,
) async {
  final lessonId = item.mediaItemId;
  if (lessonId == null) return;
  try {
    final content = await ref
        .read(catalogRepositoryProvider)
        .loadStudyContent(lessonId);
    if (!context.mounted) return;
    final summary = content.summaryMarkdown?.trim();
    if (content.summaryStatus != 'READY' ||
        summary == null ||
        summary.isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('该课时尚无学习内容')));
      return;
    }
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.68,
        maxChildSize: 0.9,
        builder: (context, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 36),
          children: [
            const ShanganEyebrow('AI 识别摘要'),
            const SizedBox(height: 8),
            Text(item.title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            ShanganMarkdown(data: summary),
          ],
        ),
      ),
    );
  } catch (error) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(shanganErrorMessage(error, '摘要加载失败，请检查网络后重试'))),
    );
  }
}

final class BattleOrderGroup {
  const BattleOrderGroup({
    required this.key,
    required this.title,
    required this.items,
  });

  final String key;
  final String title;
  final List<PlanItemData> items;
}

/// 连续相同课程归为一组，模拟考试单独成组且仍排在课时之后。
List<BattleOrderGroup> groupBattleOrderItems(List<PlanItemData> items) {
  final groups = <BattleOrderGroup>[];
  for (final item in items) {
    final isDebt = item.itemType == 'DEBT_REPAYMENT';
    final key = isDebt
        ? 'debt'
        : (item.courseId ??
              (item.itemType == 'MOCK_EXAM' ? 'mock-exam' : 'other'));
    final title = isDebt
        ? '学习欠债'
        : (item.courseName ?? (item.itemType == 'MOCK_EXAM' ? '模拟考试' : '其它任务'));
    if (groups.isEmpty || groups.last.key != key) {
      groups.add(BattleOrderGroup(key: key, title: title, items: [item]));
    } else {
      groups.last.items.add(item);
    }
  }
  return groups;
}

double battleOrderProgress(List<PlanItemData> items) {
  final countable = items
      .where((item) => item.itemType != 'REVIEW_SHORTCUT')
      .toList();
  if (countable.isEmpty) return 0;
  final completed = countable
      .where((item) => item.status == 'COMPLETED')
      .length;
  if (completed > 0) return completed / countable.length;
  final planned = countable.fold<int>(
    0,
    (sum, item) => sum + item.plannedSeconds,
  );
  final done = countable.fold<int>(
    0,
    (sum, item) => sum + item.completedSeconds,
  );
  if (planned <= 0) return 0;
  return (done / planned).clamp(0, 1);
}

({String label, ShanganTagTone tone}) battleOrderItemStatus(PlanItemData item) {
  if (item.itemType == 'MOCK_EXAM') {
    return switch (item.mockExamSessionStatus) {
      'RUNNING' => (label: '考试中', tone: ShanganTagTone.info),
      'AWAITING_UPLOAD' => (label: '待传试卷', tone: ShanganTagTone.warning),
      'COMPLETED' => (label: '已完成', tone: ShanganTagTone.success),
      'CANCELLED' => (label: '已取消', tone: ShanganTagTone.warning),
      _ =>
        item.status == 'COMPLETED'
            ? (label: '已完成', tone: ShanganTagTone.success)
            : (label: '待开始', tone: ShanganTagTone.warning),
    };
  }
  if (item.status == 'COMPLETED') {
    return (label: '已完成', tone: ShanganTagTone.success);
  }
  if (item.completedSeconds > 0) {
    return (label: '进行中', tone: ShanganTagTone.success);
  }
  return (label: '待开始', tone: ShanganTagTone.warning);
}

String battleOrderItemSubtitle(PlanItemData item) {
  switch (item.itemType) {
    case 'DEBT_REPAYMENT':
      final remaining = (item.plannedSeconds - item.completedSeconds).clamp(
        0,
        item.plannedSeconds,
      );
      if (item.sourceDebtType == 'QUIZ') {
        return '课后题 · 剩余 ${shanganDuration(remaining)}';
      }
      if (item.sourceDebtType == 'FOCUS') {
        return '专注 · 剩余 ${shanganDuration(remaining)}';
      }
      final percent = item.plannedSeconds <= 0
          ? 0
          : (item.completedSeconds * 100 ~/ item.plannedSeconds).clamp(0, 100);
      return '视频 ${shanganDuration(item.plannedSeconds)} · 已观看 $percent% · 剩余 ${shanganDuration(remaining)}';
    case 'MOCK_EXAM':
      return '模拟考试 ${shanganDuration(item.plannedSeconds)}';
    case 'REVIEW_SHORTCUT':
      return '复习 · ${shanganDuration(item.plannedSeconds)}';
    default:
      final quiz = item.quizRequired ? ' + 课后题' : '';
      return '视频 ${shanganDuration(item.plannedSeconds)}$quiz';
  }
}

PlanItemData? firstActionableItem(List<PlanItemData> items) {
  return firstInProgressItem(items) ?? _firstWhere(_isUnfinishedWork, items);
}

/// 首页一键继续只指向已经开始但未完成的任务。
PlanItemData? firstInProgressItem(List<PlanItemData> items) {
  return _firstWhere(_isInProgressWork, items);
}

/// 首页接续按钮对准展示列表第一项可播课时，欠债视频不会被跳过。
PlanItemData? firstResumableItem(List<PlanItemData> items) {
  for (final item in homeResumeItems(items)) {
    if (battleOrderItemPlayable(item)) return item;
  }
  return null;
}

/// 首页接续队列：未完成欠债排在今日任务前面，按钮和列表第一项保持一致。
List<PlanItemData> homeResumeItems(List<PlanItemData> items) {
  final unfinished = items.where(_isUnfinishedWork).toList();
  unfinished.sort((left, right) {
    final leftDebt = _isDebtItem(left);
    final rightDebt = _isDebtItem(right);
    if (leftDebt != rightDebt) return leftDebt ? -1 : 1;
    final leftProgress = _isInProgressWork(left);
    final rightProgress = _isInProgressWork(right);
    if (leftProgress != rightProgress) return leftProgress ? -1 : 1;
    if (leftProgress && rightProgress) {
      return right.completedSeconds.compareTo(left.completedSeconds);
    }
    return left.sortOrder.compareTo(right.sortOrder);
  });
  return unfinished;
}

PlanItemData? _firstWhere(
  bool Function(PlanItemData) match,
  List<PlanItemData> items,
) {
  for (final item in items) {
    if (match(item)) return item;
  }
  return null;
}

/// 把未入单的开放欠债插到今日任务前面，首页和学习页共用同一张表。
DailyPlanData mergeOpenDebts(DailyPlanData plan, List<LearningDebtData> debts) {
  final debtById = {for (final debt in debts) debt.id: debt};
  final claimed = {
    for (final item in plan.items)
      if (item.debtId != null) item.debtId!,
  };
  final studyMediaIds = {
    for (final item in plan.items)
      if (item.mediaItemId != null &&
          (item.itemType == 'VIDEO' || item.itemType == 'DEBT_REPAYMENT'))
        item.mediaItemId!,
  };
  final extras = <PlanItemData>[];
  var order = -debts.length;
  for (final debt in debts) {
    if (debt.status == 'PAID' || debt.status == 'WAIVED') continue;
    if (claimed.contains(debt.id)) continue;
    // 今日已有同一课时的学习/还债任务时，视频欠债不再单独占一行。复习入口不吞掉答题欠债。
    if (debt.debtType == 'VIDEO_WATCH' &&
        debt.mediaItemId != null &&
        studyMediaIds.contains(debt.mediaItemId)) {
      continue;
    }
    extras.add(_planItemFromOpenDebt(debt, order++));
  }
  final rest =
      [
        for (final item in plan.items)
          _withDebtProgress(item, debtById[item.debtId]),
      ]..sort((left, right) {
        final leftDebt = left.itemType == 'DEBT_REPAYMENT' ? 0 : 1;
        final rightDebt = right.itemType == 'DEBT_REPAYMENT' ? 0 : 1;
        if (leftDebt != rightDebt) return leftDebt.compareTo(rightDebt);
        return left.sortOrder.compareTo(right.sortOrder);
      });
  return DailyPlanData(
    id: plan.id,
    date: plan.date,
    status: plan.status,
    version: plan.version,
    items: [...extras, ...rest],
  );
}

/// 欠债账本里 original 是开债时剩余量，必须加上 baseline 才是整集已学进度。
({int planned, int completed}) debtDisplayProgress(LearningDebtData debt) {
  final remaining = math.max(0, debt.remainingSeconds);
  final originalRemaining = debt.originalSeconds > 0
      ? debt.originalSeconds
      : remaining;
  final baseline = math.max(0, debt.baselineCompletedSeconds);
  final repaid = (originalRemaining - remaining).clamp(0, originalRemaining);
  return (planned: baseline + originalRemaining, completed: baseline + repaid);
}

/// 未入单欠债用课时可信进度续播；合成 ID 不能当作 planItemId 提交。
PlanItemData _planItemFromOpenDebt(LearningDebtData debt, int sortOrder) {
  final progress = debtDisplayProgress(debt);
  return PlanItemData(
    id: 'debt:${debt.id}',
    itemType: 'DEBT_REPAYMENT',
    title: debt.title,
    mediaItemId: debt.mediaItemId,
    mockExamPresetId: null,
    mockExamName: null,
    plannedSeconds: progress.planned,
    completedSeconds: progress.completed,
    status: 'PENDING',
    sortOrder: sortOrder,
    immutable: true,
    debtId: debt.id,
    sourceDebtType: debt.debtType,
  );
}

/// 已入单的还债任务也用欠债账本回显整集进度，避免只看到当天增量。
PlanItemData _withDebtProgress(PlanItemData item, LearningDebtData? debt) {
  if (item.itemType != 'DEBT_REPAYMENT' || debt == null) return item;
  final progress = debtDisplayProgress(debt);
  return PlanItemData(
    id: item.id,
    itemType: item.itemType,
    title: item.title,
    mediaItemId: item.mediaItemId ?? debt.mediaItemId,
    mockExamPresetId: item.mockExamPresetId,
    mockExamName: item.mockExamName,
    plannedSeconds: progress.planned,
    completedSeconds: progress.completed,
    status: item.status,
    sortOrder: item.sortOrder,
    immutable: item.immutable,
    courseId: item.courseId,
    courseName: item.courseName,
    quizRequired: item.quizRequired,
    debtId: item.debtId,
    sourceDebtType: debt.debtType,
    mockExamSessionStatus: item.mockExamSessionStatus,
  );
}

bool _showsWatchProgress(PlanItemData item) {
  if (item.itemType == 'VIDEO') return true;
  return item.itemType == 'DEBT_REPAYMENT' &&
      item.sourceDebtType != 'QUIZ' &&
      item.sourceDebtType != 'FOCUS';
}

/// 视频欠债、今日课时和模拟考试都可以从首页直接打开。
bool battleOrderItemPlayable(PlanItemData item) {
  if (item.itemType == 'MOCK_EXAM') return true;
  if (item.sourceDebtType == 'FOCUS') return false;
  return item.mediaItemId != null && item.mediaItemId!.isNotEmpty;
}

/// 合成欠债 ID 不能传给观看会话；直接按课时续播，服务端会按同一视频对账还债。
Uri? battleOrderPlaybackUri(PlanItemData item) {
  if (!battleOrderItemPlayable(item)) return null;
  if (item.itemType == 'MOCK_EXAM') {
    return Uri(
      path: '/mock-exam',
      queryParameters: {'planItemId': item.id, 'title': item.title},
    );
  }
  final mediaItemId = item.mediaItemId;
  if (mediaItemId == null) return null;
  if (item.sourceDebtType == 'QUIZ') {
    return Uri(path: '/quiz/$mediaItemId');
  }
  final planItemId = item.id.startsWith('debt:') ? null : item.id;
  return Uri(
    path: '/player/$mediaItemId',
    queryParameters: {
      'title': item.title,
      if (planItemId != null && planItemId.isNotEmpty) 'planItemId': planItemId,
    },
  );
}

void openBattleOrderItem(BuildContext context, PlanItemData item) {
  final uri = battleOrderPlaybackUri(item);
  if (uri == null) return;
  context.push(uri.toString());
}

bool battleOrderEditable(String status) =>
    const {'NONE', 'DRAFT', 'ACTIVE'}.contains(status);

String _planStatusLabel(String status) => switch (status) {
  'DRAFT' => '草稿',
  'ACTIVE' => '已锁定',
  'LOCKED' => '已锁定',
  'COMPLETED' => '已完成',
  'ABANDONED' => '已结算',
  'CLOSED_WITH_DEBT' => '欠债结算',
  _ => '未创建',
};

ShanganTagTone _planTone(String status) => switch (status) {
  'COMPLETED' => ShanganTagTone.success,
  'CLOSED_WITH_DEBT' => ShanganTagTone.risk,
  'ACTIVE' || 'LOCKED' || 'DRAFT' => ShanganTagTone.info,
  _ => ShanganTagTone.neutral,
};

bool _isDebtItem(PlanItemData item) => item.itemType == 'DEBT_REPAYMENT';

bool _isUnfinishedWork(PlanItemData item) =>
    item.itemType != 'REVIEW_SHORTCUT' && item.status != 'COMPLETED';

bool _isInProgressWork(PlanItemData item) =>
    _isUnfinishedWork(item) && item.completedSeconds > 0;

/// 作战单卡片内固定露出的课时条数，超出后在卡片内滚动。
const battleOrderVisibleItemCount = 3;
const _battleOrderItemExtent = 100.0;
const _battleOrderGroupHeaderExtent = 64.0;

/// 课程名用高饱和色区分分组；课时标题保持原色。
Color _statusTextColor(ShanganTagTone tone) => switch (tone) {
  ShanganTagTone.success => ShanganColors.green,
  ShanganTagTone.warning => ShanganColors.ochre,
  ShanganTagTone.risk => ShanganColors.red,
  ShanganTagTone.info => ShanganColors.blue,
  ShanganTagTone.neutral => ShanganColors.mutedInk,
};

Color _courseTitleColor({required String kindLabel, required bool debtAccent}) {
  if (debtAccent || kindLabel == '欠债') return ShanganColors.red;
  return ShanganColors.course;
}

/// 课时过多时限制可视高度，并始终显示滚动条提示还能往下看。
final class _BattleOrderScrollArea extends StatefulWidget {
  const _BattleOrderScrollArea({
    required this.itemCount,
    required this.grouped,
    required this.child,
  });

  final int itemCount;
  final bool grouped;
  final Widget child;

  @override
  State<_BattleOrderScrollArea> createState() => _BattleOrderScrollAreaState();
}

final class _BattleOrderScrollAreaState extends State<_BattleOrderScrollArea>
    with TickerProviderStateMixin {
  final _controller = ScrollController();
  late final ShanganIdleMotion _motion;

  @override
  void initState() {
    super.initState();
    _motion = ShanganIdleMotion(
      vsync: this,
      onTick: () {
        if (mounted) setState(() {});
      },
      pulseDuration: const Duration(milliseconds: 640),
      idlePeriod: const Duration(seconds: 4),
    );
    _controller.addListener(_handleScroll);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _motion.startIdle(_reduceMotion);
      setState(() {});
    });
  }

  @override
  void dispose() {
    _controller
      ..removeListener(_handleScroll)
      ..dispose();
    _motion.dispose();
    super.dispose();
  }

  void _handleScroll() {
    if (!mounted) return;
    // 真在滚时不要抢戏，停住抖动并重新计时。
    _motion.restartIdle(_reduceMotion);
    setState(() {});
  }

  bool _reduceMotion() => !mounted || MediaQuery.disableAnimationsOf(context);

  @override
  Widget build(BuildContext context) {
    if (widget.itemCount <= battleOrderVisibleItemCount) {
      return widget.child;
    }
    final maxHeight =
        (widget.grouped ? _battleOrderGroupHeaderExtent : 0) +
        battleOrderVisibleItemCount * _battleOrderItemExtent;
    final pulse = _motion.pulseValue;
    final wave = math.sin(math.pi * pulse);
    // 接近底部就往上点头，否则往下点头，提示还能继续滑。
    final nudge = wave * (_scrollProgress > 0.85 ? -9.0 : 9.0);
    return SizedBox(
      key: const Key('battleOrderScrollbar'),
      height: maxHeight,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: SingleChildScrollView(
              controller: _controller,
              child: widget.child,
            ),
          ),
          const SizedBox(width: 10),
          SizedBox(
            width: 14,
            child: CustomPaint(
              painter: _BattleOrderScrollRailPainter(
                progress: _scrollProgress,
                thumbFraction: _thumbFraction,
                nudge: nudge,
                pulse: pulse,
              ),
            ),
          ),
        ],
      ),
    );
  }

  double get _scrollProgress {
    if (!_controller.hasClients) return 0;
    final extent = _controller.position.maxScrollExtent;
    if (extent <= 0) return 0;
    return (_controller.offset / extent).clamp(0, 1);
  }

  double get _thumbFraction {
    if (!_controller.hasClients) return 0.35;
    final position = _controller.position;
    final total = position.maxScrollExtent + position.viewportDimension;
    if (total <= 0) return 0.35;
    return (position.viewportDimension / total).clamp(0.22, 0.85);
  }
}

/// 右侧独立滚动轨：不覆盖课时文字，滑块带高光和握持刻度。
final class _BattleOrderScrollRailPainter extends CustomPainter {
  const _BattleOrderScrollRailPainter({
    required this.progress,
    required this.thumbFraction,
    this.nudge = 0,
    this.pulse = 0,
  });

  final double progress;
  final double thumbFraction;
  final double nudge;
  final double pulse;

  @override
  void paint(Canvas canvas, Size size) {
    final breathe = math.sin(math.pi * pulse);
    final centerX = size.width / 2;
    final track = RRect.fromLTRBR(
      centerX - 2,
      2,
      centerX + 2,
      size.height - 2,
      const Radius.circular(999),
    );
    canvas.drawRRect(track, Paint()..color = const Color(0xFFEAF1FB));
    canvas.drawRRect(
      track,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1 + 0.4 * breathe
        ..color = Color.lerp(
          const Color(0xFFC5D4EA),
          const Color(0xFF5B8FD4),
          0.35 * breathe,
        )!,
    );

    final thumbHeight = (size.height * thumbFraction).clamp(
      32.0,
      size.height - 8,
    );
    final travel = size.height - 4 - thumbHeight;
    final top = (2 + travel * progress + nudge).clamp(
      2.0,
      size.height - 2 - thumbHeight,
    );
    final halfWidth = 4 + breathe;
    final thumbRect = Rect.fromLTWH(
      centerX - halfWidth,
      top,
      halfWidth * 2,
      thumbHeight,
    );
    final thumb = RRect.fromRectAndRadius(thumbRect, const Radius.circular(4));
    canvas.drawRRect(
      thumb,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color.lerp(const Color(0xFF5B8FD4), Colors.white, 0.18 * breathe)!,
            const Color(0xFF2C68B7),
          ],
        ).createShader(thumbRect),
    );
    canvas.drawRRect(
      RRect.fromLTRBR(
        thumbRect.left + 1.5,
        thumbRect.top + 2,
        thumbRect.right - 1.5,
        thumbRect.top + 7,
        const Radius.circular(2),
      ),
      Paint()..color = Colors.white.withValues(alpha: 0.35 + 0.2 * breathe),
    );
    final gripY = thumbRect.center.dy;
    final grip = Paint()
      ..color = Colors.white.withValues(alpha: 0.7)
      ..strokeWidth = 1.2
      ..strokeCap = StrokeCap.round;
    for (final offset in const [-3.5, 0.0, 3.5]) {
      canvas.drawLine(
        Offset(centerX - 2, gripY + offset),
        Offset(centerX + 2, gripY + offset),
        grip,
      );
    }
  }

  @override
  bool shouldRepaint(_BattleOrderScrollRailPainter oldDelegate) =>
      oldDelegate.progress != progress ||
      oldDelegate.thumbFraction != thumbFraction ||
      oldDelegate.nudge != nudge ||
      oldDelegate.pulse != pulse;
}
