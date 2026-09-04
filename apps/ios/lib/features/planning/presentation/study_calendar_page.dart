import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/planning/presentation/battle_order_widgets.dart';

/// 切回学习 Tab 或从编排页返回时递增，驱动日历重新读取作战单。
final studyCalendarRefreshListenable = ValueNotifier<int>(0);

void bumpStudyCalendarRefresh() => studyCalendarRefreshListenable.value++;

/// 学习 Tab：按月查看作战单完成情况，当天把欠债和今日任务放进同一张表。
final class StudyCalendarPage extends ConsumerStatefulWidget {
  const StudyCalendarPage({super.key, this.today});

  /// 仅测试注入“今天”，生产环境使用设备本地日期。
  final DateTime? today;

  @override
  ConsumerState<StudyCalendarPage> createState() => _StudyCalendarPageState();
}

final class _StudyCalendarPageState extends ConsumerState<StudyCalendarPage>
    with WidgetsBindingObserver {
  late DateTime _today;
  late DateTime _visibleMonth;
  late DateTime _selected;
  List<PlanCalendarDay> _days = const [];
  final Map<String, DailyPlanData> _plans = {};
  List<LearningDebtData> _debts = const [];
  Object? _error;
  bool _loading = true;
  bool _calendarExpanded = false;

  @override
  void initState() {
    super.initState();
    _today = _deviceToday();
    _visibleMonth = DateTime(_today.year, _today.month);
    _selected = _today;
    WidgetsBinding.instance.addObserver(this);
    studyCalendarRefreshListenable.addListener(_handleExternalRefresh);
    unawaited(_initialLoad());
  }

  DateTime _deviceToday() => shanganDeviceToday(widget.today);

  Future<void> _initialLoad() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    await Future.wait([_reloadMonth(), _reloadSelectedPlan()]);
    if (mounted) setState(() => _loading = false);
  }

  void _handleExternalRefresh() => unawaited(_refreshForCurrentDay());

  /// 跨日后仍停留在前台时，把“今天”滚到设备当前日期再拉作战单。
  bool _rollTodayIfNeeded() {
    final now = _deviceToday();
    if (shanganSameDay(now, _today)) return false;
    final viewingToday = shanganSameDay(_selected, _today);
    _today = now;
    if (viewingToday) {
      _selected = _today;
      _visibleMonth = DateTime(_today.year, _today.month);
    }
    return true;
  }

  Future<void> _refreshForCurrentDay() async {
    final rolled = _rollTodayIfNeeded();
    if (!mounted) return;
    if (rolled) setState(() {});
    // 返回首页/学习后同时刷新月历标记和当日作战单，避免考试完成后仍显示未完成。
    await Future.wait([_reloadMonth(), _reloadSelectedPlan()]);
  }

  @override
  void didUpdateWidget(StudyCalendarPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.today != widget.today) {
      unawaited(_refreshForCurrentDay());
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_refreshForCurrentDay());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    studyCalendarRefreshListenable.removeListener(_handleExternalRefresh);
    super.dispose();
  }

  Future<void> _reloadMonth() async {
    try {
      final from = DateTime(_visibleMonth.year, _visibleMonth.month, 1);
      final to = DateTime(_visibleMonth.year, _visibleMonth.month + 1, 0);
      final days = await ref
          .read(planRepositoryProvider)
          .loadCalendar(from: from, to: to);
      if (!mounted) return;
      setState(() {
        _days = days;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error);
    }
  }

  Future<void> _reloadSelectedPlan() async {
    try {
      final repo = ref.read(planRepositoryProvider);
      final plan = await repo.load(_selected);
      final debts = shanganSameDay(_selected, _today)
          ? await repo.loadDebts()
          : const <LearningDebtData>[];
      if (!mounted) return;
      setState(() {
        _plans[shanganDateKey(_selected)] = plan;
        _debts = debts;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error);
    }
  }

  void _goToday() {
    _rollTodayIfNeeded();
    setState(() {
      _selected = _today;
      _visibleMonth = DateTime(_today.year, _today.month);
    });
    unawaited(_reloadMonth());
    unawaited(_reloadSelectedPlan());
  }

  @override
  Widget build(BuildContext context) {
    if (!shanganSameDay(_deviceToday(), _today)) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(_refreshForCurrentDay());
      });
    }
    return Scaffold(
      appBar: AppBar(title: const Text('学习')),
      body: _loading && _days.isEmpty
          ? const ShanganLoading('正在读取作战日历')
          : ListView(
              padding: shanganPagePadding,
              children: [
                _CalendarMonth(
                  month: _visibleMonth,
                  today: _today,
                  selected: _selected,
                  days: _days,
                  expanded: _calendarExpanded,
                  onToggleExpanded: () =>
                      setState(() => _calendarExpanded = !_calendarExpanded),
                  onPrevious: () {
                    setState(() {
                      _visibleMonth = DateTime(
                        _visibleMonth.year,
                        _visibleMonth.month - 1,
                      );
                    });
                    unawaited(_reloadMonth());
                  },
                  onNext: () {
                    setState(() {
                      _visibleMonth = DateTime(
                        _visibleMonth.year,
                        _visibleMonth.month + 1,
                      );
                    });
                    unawaited(_reloadMonth());
                  },
                  onSelect: (date) {
                    setState(() => _selected = date);
                    unawaited(_reloadSelectedPlan());
                  },
                  onToday: _goToday,
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  const ShanganNotice(
                    title: '作战单刷新失败',
                    message: '日历仍可切换，请稍后重试或下拉切回本月。',
                    tone: ShanganTagTone.warning,
                  ),
                ],
                const SizedBox(height: 22),
                _planPanel(),
              ],
            ),
    );
  }

  Widget _planPanel() {
    final date = _selected;
    var plan =
        _plans[shanganDateKey(date)] ??
        DailyPlanData(
          id: null,
          date: date,
          status: 'NONE',
          version: 0,
          items: const [],
        );
    final isToday = shanganSameDay(date, _today);
    final isPast = date.isBefore(_today);
    if (isToday) {
      plan = mergeOpenDebts(plan, _debts);
    }
    final readOnly = isPast || !battleOrderEditable(plan.status);
    final actionable = firstActionableItem(plan.items);
    return BattleOrderDayPanel(
      key: Key('plan-panel-${shanganDateKey(date)}'),
      plan: plan,
      grouped: true,
      readOnly: readOnly,
      showDebtMarks: isPast,
      eyebrow: isToday ? '今日作战单' : '${date.month} 月 ${date.day} 日作战单',
      continueLabel: isToday && actionable != null
          ? '继续${actionable.title}'
          : null,
      onEdit: readOnly
          ? null
          : () => context.push('/plan?date=${shanganDateKey(date)}'),
      onOpenItem: (item) =>
          unawaited(_openItem(item, isToday: isToday, isPast: isPast)),
    );
  }

  Future<void> _openItem(
    PlanItemData item, {
    required bool isToday,
    required bool isPast,
  }) async {
    if (item.mediaItemId == null && item.itemType != 'MOCK_EXAM') return;
    if (isToday || isPast) {
      openBattleOrderItem(context, item);
      return;
    }
    final action = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('未来日的课时不能直接计为学习'),
        content: Text('「${item.title}」在未来作战单中。要观看请加入今日作战单，或只临时看视频流。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, 'cancel'),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, 'preview'),
            child: const Text('临时播放'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, 'today'),
            child: const Text('加入今日作战单'),
          ),
        ],
      ),
    );
    if (!mounted || action == null || action == 'cancel') return;
    if (action == 'preview' && item.mediaItemId != null) {
      context.push(
        Uri(
          path: '/player/${item.mediaItemId}',
          queryParameters: {'title': item.title, 'preview': 'true'},
        ).toString(),
      );
      return;
    }
    if (action == 'today' && item.mediaItemId != null) {
      await _addLessonToToday(item);
    }
  }

  Future<void> _addLessonToToday(PlanItemData item) async {
    final repo = ref.read(planRepositoryProvider);
    final today = await repo.loadToday();
    if (today.items.any(
      (existing) => existing.mediaItemId == item.mediaItemId,
    )) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('今日作战单已包含该课时')));
      return;
    }
    if (!battleOrderEditable(today.status) && today.status != 'NONE') {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('今日作战单已结算，不能再加入课时')));
      return;
    }
    final drafts = [
      ...today.items.map(BattleOrderDraft.fromSaved),
      BattleOrderDraft(
        existingItemId: null,
        itemType: item.itemType == 'REVIEW_SHORTCUT'
            ? 'REVIEW_SHORTCUT'
            : 'VIDEO',
        title: item.title,
        mediaItemId: item.mediaItemId,
        mockExamPresetId: null,
        plannedSeconds: item.plannedSeconds,
        immutable: false,
        catalogOrder: item.sortOrder,
        courseId: item.courseId,
        courseName: item.courseName,
      ),
    ];
    await repo.saveToday(expectedVersion: today.version, items: drafts);
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('已加入今日作战单')));
  }
}

final class _CalendarMonth extends StatelessWidget {
  const _CalendarMonth({
    required this.month,
    required this.today,
    required this.selected,
    required this.days,
    required this.onPrevious,
    required this.onNext,
    required this.onSelect,
    required this.onToday,
    required this.expanded,
    required this.onToggleExpanded,
  });

  final DateTime month;
  final DateTime today;
  final DateTime selected;
  final List<PlanCalendarDay> days;
  final VoidCallback onPrevious;
  final VoidCallback onNext;
  final ValueChanged<DateTime> onSelect;
  final VoidCallback onToday;
  final bool expanded;
  final VoidCallback onToggleExpanded;

  @override
  Widget build(BuildContext context) {
    final marks = {
      for (final day in days)
        shanganDateKey(DateTime(day.date.year, day.date.month, day.date.day)):
            day,
    };
    final first = DateTime(month.year, month.month, 1);
    final daysInMonth = DateTime(month.year, month.month + 1, 0).day;
    final leading = first.weekday - 1;
    const labels = ['一', '二', '三', '四', '五', '六', '日'];
    return ShanganSurface(
      borderColor: ShanganColors.blue,
      padding: const EdgeInsets.fromLTRB(8, 6, 8, 8),
      child: Column(
        children: [
          SizedBox(
            height: 36,
            child: Row(
              children: [
                IconButton(
                  key: const Key('calendarPreviousMonth'),
                  tooltip: '上个月',
                  visualDensity: VisualDensity.compact,
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(
                    minWidth: 36,
                    minHeight: 36,
                  ),
                  onPressed: onPrevious,
                  icon: const Icon(Icons.chevron_left, size: 20),
                ),
                Expanded(
                  child: Text(
                    '${month.year} 年 ${month.month} 月',
                    textAlign: TextAlign.center,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                TextButton(
                  key: const Key('calendarToday'),
                  onPressed: onToday,
                  style: TextButton.styleFrom(
                    visualDensity: VisualDensity.compact,
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    minimumSize: const Size(44, 36),
                  ),
                  child: const Text('今天'),
                ),
                IconButton(
                  key: const Key('calendarNextMonth'),
                  tooltip: '下个月',
                  visualDensity: VisualDensity.compact,
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(
                    minWidth: 36,
                    minHeight: 36,
                  ),
                  onPressed: onNext,
                  icon: const Icon(Icons.chevron_right, size: 20),
                ),
                if (expanded)
                  TextButton(
                    key: const Key('calendarToggle'),
                    onPressed: onToggleExpanded,
                    style: TextButton.styleFrom(
                      visualDensity: VisualDensity.compact,
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      minimumSize: const Size(44, 36),
                    ),
                    child: const Text('收起'),
                  ),
              ],
            ),
          ),
          ClipRect(
            child: AnimatedSize(
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeInOut,
              alignment: Alignment.topCenter,
              child: expanded
                  ? Column(
                      children: [
                        Row(
                          children: [
                            for (final label in labels)
                              Expanded(
                                child: Text(
                                  label,
                                  textAlign: TextAlign.center,
                                  style: Theme.of(
                                    context,
                                  ).textTheme.bodySmall?.copyWith(fontSize: 11),
                                ),
                              ),
                          ],
                        ),
                        const SizedBox(height: 2),
                        for (
                          var row = 0;
                          row < ((leading + daysInMonth) / 7).ceil();
                          row++
                        )
                          Row(
                            children: [
                              for (var col = 0; col < 7; col++)
                                Expanded(
                                  child: _cell(
                                    context,
                                    marks,
                                    leading,
                                    daysInMonth,
                                    row * 7 + col,
                                  ),
                                ),
                            ],
                          ),
                      ],
                    )
                  : _collapsedPeek(context, marks),
            ),
          ),
        ],
      ),
    );
  }

  /// 折叠态展示已选日期摘要和淡化星期条，整块可点按展开月历。
  Widget _collapsedPeek(
    BuildContext context,
    Map<String, PlanCalendarDay> marks,
  ) {
    final mark = marks[shanganDateKey(selected)];
    final isToday = shanganSameDay(selected, today);
    final dateLabel = isToday
        ? '今天 ${selected.month} 月 ${selected.day} 日'
        : '${selected.month} 月 ${selected.day} 日';
    final summary = mark == null
        ? '当天还没有作战单，点按展开月历'
        : mark.completed
        ? '${mark.itemCount} 节已完成 · 点按展开月历'
        : '${mark.itemCount} 节${_formatHours(mark.plannedSeconds)} · 点按展开月历';
    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: const Key('calendarToggle'),
        onTap: onToggleExpanded,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(8, 6, 8, 4),
          child: Column(
            children: [
              Row(
                children: [
                  Container(
                    width: 48,
                    height: 48,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: ShanganColors.blueSoft,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(
                        color: ShanganColors.blue.withValues(alpha: 0.4),
                      ),
                    ),
                    child: Text(
                      '${selected.day}',
                      style: shanganNumberStyle(
                        context,
                        fontSize: 22,
                      ).copyWith(color: ShanganColors.blue),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          dateLabel,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 2),
                        Text(
                          summary,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  Column(
                    children: [
                      Text(
                        '展开月历',
                        style: Theme.of(context).textTheme.labelLarge?.copyWith(
                          color: ShanganColors.blue,
                        ),
                      ),
                      const Icon(
                        Icons.expand_more,
                        color: ShanganColors.blue,
                        size: 22,
                      ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Opacity(
                opacity: 0.38,
                child: Row(
                  children: [
                    for (final label in const [
                      '一',
                      '二',
                      '三',
                      '四',
                      '五',
                      '六',
                      '日',
                    ])
                      Expanded(
                        child: Text(
                          label,
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 6),
              DecoratedBox(
                decoration: BoxDecoration(
                  color: ShanganColors.inkSoft,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const SizedBox(
                  width: double.infinity,
                  height: 16,
                  child: Icon(
                    Icons.more_horiz,
                    size: 16,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _cell(
    BuildContext context,
    Map<String, PlanCalendarDay> marks,
    int leading,
    int daysInMonth,
    int slot,
  ) {
    final dayNumber = slot - leading + 1;
    if (dayNumber < 1 || dayNumber > daysInMonth) {
      return const SizedBox(height: 52);
    }
    final date = DateTime(month.year, month.month, dayNumber);
    final mark = marks[shanganDateKey(date)];
    final isToday = shanganSameDay(date, today);
    final isSelected = shanganSameDay(date, selected);
    return InkWell(
      key: Key('calendar-day-${shanganDateKey(date)}'),
      onTap: () => onSelect(date),
      child: SizedBox(
        height: 52,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: isSelected ? ShanganColors.blueSoft : Colors.transparent,
            border: Border.all(
              color: isToday ? ShanganColors.blue : Colors.transparent,
              width: isToday ? 1 : 0,
            ),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    '$dayNumber',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  _DayDot(date: date, mark: mark, isPast: date.isBefore(today)),
                ],
              ),
              if (mark != null && mark.itemCount > 0)
                Text(
                  '${mark.itemCount}节${_formatHours(mark.plannedSeconds)}',
                  style: Theme.of(
                    context,
                  ).textTheme.bodySmall?.copyWith(fontSize: 8, height: 1),
                  maxLines: 1,
                  overflow: TextOverflow.clip,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

String _formatHours(int seconds) => shanganDuration(seconds);

/// 绿点表示当日作战完成，红点只标已结算欠债；没有学习记录的日期不打点。
final class _DayDot extends StatelessWidget {
  const _DayDot({required this.date, required this.mark, required this.isPast});

  final DateTime date;
  final PlanCalendarDay? mark;
  final bool isPast;

  @override
  Widget build(BuildContext context) {
    Color? color;
    if (mark?.completed == true) {
      color = ShanganColors.green;
    } else if (mark?.hasDebt == true && isPast) {
      color = ShanganColors.red;
    }
    if (color == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(left: 2),
      child: Container(
        key: Key('calendar-dot-${shanganDateKey(date)}'),
        width: 6,
        height: 6,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
    );
  }
}
