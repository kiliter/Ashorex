import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';
import 'package:shangan_ios/features/focus/presentation/focus_duration_sheet.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/planning/presentation/battle_order_widgets.dart';

/// 切回首页或 App 回到前台时递增，驱动重新读取今日作战单。
final homeRefreshListenable = ValueNotifier<int>(0);

void bumpHomeRefresh() => homeRefreshListenable.value++;

/// 首页展示学习压力，并通过右滑或显式按钮打开独立小工具菜单。
final class HomePage extends ConsumerStatefulWidget {
  const HomePage({super.key});

  @override
  ConsumerState<HomePage> createState() => _HomePageState();
}

final class _HomePageState extends ConsumerState<HomePage> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();
  int _examIndex = 0;
  late Future<_HomeSnapshot> _home;

  /// 引导页正在栈上，禁止再次压入，避免同一时刻叠加多个考试目标页。
  bool _guidingExamGoal = false;

  /// 本次首页会话已自动引导过一次；返回后即使仍无考试目标也只保留手动入口。
  bool _examGoalGuideRequested = false;

  @override
  void initState() {
    super.initState();
    // 考试 Tab 切换只改本地下标，不重新拉取首页快照。
    _home = _load();
    homeRefreshListenable.addListener(_reloadToday);
  }

  /// 打开考试目标设置页，返回后重新读取首页快照。
  ///
  /// 引导页保存后以 pop 返回，这里必须主动重拉，否则首页仍持有空考试列表并再次跳转，
  /// 用户会被困在考试目标页无法回到首页。
  Future<void> _guideExamGoalSetup() async {
    if (!mounted || _guidingExamGoal) return;
    _guidingExamGoal = true;
    _examGoalGuideRequested = true;
    try {
      await context.push('/exam-goal');
    } finally {
      _guidingExamGoal = false;
      if (mounted) _reloadToday();
    }
  }

  void _reloadToday() {
    if (!mounted) return;
    setState(() {
      _home = _load();
    });
  }

  @override
  void dispose() {
    homeRefreshListenable.removeListener(_reloadToday);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: _scaffoldKey,
      appBar: AppBar(
        title: const Text('首页'),
        actions: [
          TextButton.icon(
            key: const Key('homeWidgetsButton'),
            onPressed: _openWidgets,
            icon: const Icon(Icons.widgets_outlined),
            label: const Text('小工具'),
          ),
          const SizedBox(width: 8),
        ],
      ),
      endDrawer: _HomeWidgetsDrawer(onStartFocus: _startFocus),
      body: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onHorizontalDragEnd: (details) {
          if ((details.primaryVelocity ?? 0) > 280) _openWidgets();
        },
        child: FutureBuilder<_HomeSnapshot>(
          future: _home,
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const ShanganLoading('正在核对今日学习数据');
            }
            if (snapshot.hasError || !snapshot.hasData) {
              return const Center(child: Text('首页加载失败，请稍后重试'));
            }
            final dashboard = snapshot.data!.dashboard;
            final plan = mergeOpenDebts(
              snapshot.data!.plan,
              snapshot.data!.debts,
            );
            if (dashboard.exams.isEmpty) {
              if (!_examGoalGuideRequested) {
                WidgetsBinding.instance.addPostFrameCallback(
                  (_) => _guideExamGoalSetup(),
                );
              }
              return _ExamGoalEmptyState(onSetup: _guideExamGoalSetup);
            }
            final examIndex = _examIndex.clamp(0, dashboard.exams.length - 1);
            final overview = dashboard.exams[examIndex];
            final exam = overview.exam;
            final pressure = overview.progress;
            final atRisk = pressure.riskStatus == 'AT_RISK';
            final lessonProgress = pressure.totalLessons == 0
                ? 0.0
                : pressure.completedLessons / pressure.totalLessons;
            return ListView(
              padding: shanganPagePadding,
              children: [
                ShanganSurface(
                  borderColor: ShanganColors.blue,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(
                        height: 36,
                        child: ListView.separated(
                          key: const Key('examTabs'),
                          scrollDirection: Axis.horizontal,
                          itemCount: dashboard.exams.length,
                          separatorBuilder: (_, _) => const SizedBox(width: 8),
                          itemBuilder: (context, index) {
                            return Align(
                              alignment: Alignment.centerLeft,
                              child: _ExamTabChip(
                                key: Key(
                                  'examTab-${dashboard.exams[index].exam.id}',
                                ),
                                name: dashboard.exams[index].exam.name,
                                selected: index == examIndex,
                                onTap: () => setState(() => _examIndex = index),
                              ),
                            );
                          },
                        ),
                      ),
                      const Divider(height: 16, color: ShanganColors.rule),
                      Row(
                        children: [
                          Expanded(child: ShanganEyebrow(exam.name)),
                          ShanganStatusTag(
                            atRisk ? '进度有风险' : '风险可控',
                            tone: atRisk
                                ? ShanganTagTone.risk
                                : ShanganTagTone.success,
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '距离考试 ${pressure.daysUntilExam} 天',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '当前剩余 ${pressure.remainingLessons} 课时。近 7 日每天 '
                        '${pressure.actualDailyPace.toStringAsFixed(1)} 课时，'
                        '目标要求每天 ${pressure.requiredDailyPace.toStringAsFixed(1)} 课时。',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      const SizedBox(height: 8),
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            '${pressure.daysUntilExam}',
                            style: shanganNumberStyle(context, fontSize: 56),
                          ),
                          const Padding(
                            padding: EdgeInsets.only(left: 5, bottom: 7),
                            child: Text('天'),
                          ),
                          const Spacer(),
                          Text(
                            '课程目标还剩 ${pressure.daysUntilTarget} 天\n'
                            '复习缓冲 ${exam.reviewBufferDays} 天',
                            textAlign: TextAlign.right,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                      const Divider(height: 22, color: ShanganColors.ink),
                      ShanganTrustScale(
                        label: '课程推进压力',
                        valueLabel:
                            '已完成 ${pressure.completedLessons} / ${pressure.totalLessons} 课时',
                        trustedFraction: lessonProgress,
                        positionFraction: lessonProgress,
                        thresholdFraction: 1,
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 22),
                _BattleOrderHomeCard(plan: plan),
                const SizedBox(height: 22),
                DecoratedBox(
                  decoration: const BoxDecoration(
                    border: Border(
                      top: BorderSide(color: ShanganColors.red, width: 2),
                    ),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.only(top: 18),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const ShanganEyebrow('学习欠债'),
                                  const SizedBox(height: 5),
                                  Text(
                                    shanganDuration(dashboard.openDebtSeconds),
                                    style: Theme.of(
                                      context,
                                    ).textTheme.titleLarge,
                                  ),
                                ],
                              ),
                            ),
                            TextButton(
                              onPressed: () => context.push('/debts'),
                              child: const Text('查看明细 ›'),
                            ),
                          ],
                        ),
                        const SizedBox(height: 14),
                        ShanganMetricGrid(
                          metrics: [
                            (
                              value: '${pressure.remainingLessons}',
                              label: '剩余课时',
                              tone: ShanganTagTone.info,
                            ),
                            (
                              value: pressure.requiredDailyPace.toStringAsFixed(
                                1,
                              ),
                              label: '每天最低课时',
                              tone: ShanganTagTone.success,
                            ),
                            (
                              value: pressure.actualDailyPace.toStringAsFixed(
                                1,
                              ),
                              label: '近 7 日实际速度',
                              tone: ShanganTagTone.warning,
                            ),
                            (
                              value: shanganDuration(
                                dashboard.studyTodaySeconds,
                              ),
                              label: '今日有效学习',
                              tone: ShanganTagTone.risk,
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  Future<_HomeSnapshot> _load() async {
    final dashboard = await ref.read(dashboardRepositoryProvider).load();
    final plans = ref.read(planRepositoryProvider);
    final plan = await plans.loadToday();
    final debts = await plans.loadDebts();
    return (dashboard: dashboard, plan: plan, debts: debts);
  }

  void _openWidgets() => _scaffoldKey.currentState?.openEndDrawer();

  /// 先弹出时长预设，选定后再进入专注页开始服务端倒计时。
  Future<void> _startFocus() async {
    final seconds = await showFocusDurationSheet(context);
    if (seconds == null || !mounted) return;
    _scaffoldKey.currentState?.closeEndDrawer();
    context.push(
      Uri(
        path: '/focus',
        queryParameters: {'title': '专注计时', 'plannedSeconds': '$seconds'},
      ).toString(),
    );
  }
}

typedef _HomeSnapshot = ({
  DashboardData dashboard,
  DailyPlanData plan,
  List<LearningDebtData> debts,
});

/// 考试切换芯片：每个考试都有描边和右下错开的灰卡，做出小纸片立体感。
/// 首页没有任何考试目标时的空态；自动引导只发生一次，之后保留显式入口。
final class _ExamGoalEmptyState extends StatelessWidget {
  const _ExamGoalEmptyState({required this.onSetup});

  final Future<void> Function() onSetup;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: shanganPagePadding,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const ShanganNotice(
            title: '请先设置考试目标',
            message: '设置考试日期和参与进度计算的课程后，首页才能计算学习压力。',
          ),
          const SizedBox(height: 16),
          FilledButton(
            key: const Key('setupExamGoal'),
            onPressed: onSetup,
            child: const Text('设置考试目标'),
          ),
        ],
      ),
    );
  }
}

final class _ExamTabChip extends StatelessWidget {
  const _ExamTabChip({
    required this.name,
    required this.selected,
    required this.onTap,
    super.key,
  });

  final String name;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    const radius = BorderRadius.all(Radius.circular(10));
    const shift = Offset(2, 2);
    final border = selected ? ShanganColors.blue : ShanganColors.rule;
    return Padding(
      padding: EdgeInsets.only(right: shift.dx, bottom: shift.dy),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: IgnorePointer(
              child: Transform.translate(
                offset: shift,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: const Color(0xFFC5CDD8),
                    borderRadius: radius,
                  ),
                ),
              ),
            ),
          ),
          Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: onTap,
              borderRadius: radius,
              child: Ink(
                decoration: BoxDecoration(
                  color: ShanganColors.surface,
                  borderRadius: radius,
                  border: Border.all(color: border, width: 1.5),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0x33263B60),
                      offset: Offset(0, 1),
                      blurRadius: 2,
                    ),
                  ],
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 5,
                  ),
                  child: Text(
                    name,
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                      color: selected
                          ? ShanganColors.blue
                          : ShanganColors.mutedInk,
                      height: 1.1,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 首页作战单是接续入口：未完成欠债排在今日任务之前，继续按钮播列表第一项。
final class _BattleOrderHomeCard extends StatelessWidget {
  const _BattleOrderHomeCard({required this.plan});

  final DailyPlanData plan;

  @override
  Widget build(BuildContext context) {
    final resume = firstResumableItem(plan.items);
    return BattleOrderDayPanel(
      plan: plan,
      grouped: false,
      readOnly: false,
      showDebtMarks: false,
      resumeQueue: true,
      eyebrow: '今日作战单',
      continueLabel: resume == null ? null : '继续${resume.title}',
      onEdit: () => context.push('/plan'),
    );
  }
}

/// 首页小工具独立于作战单，专注计时不会参与每日任务编排。
final class _HomeWidgetsDrawer extends StatelessWidget {
  const _HomeWidgetsDrawer({required this.onStartFocus});

  final VoidCallback onStartFocus;

  @override
  Widget build(BuildContext context) => Drawer(
    child: SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
        children: [
          Row(
            children: [
              const Expanded(child: ShanganEyebrow('首页右滑小工具')),
              IconButton(
                tooltip: '关闭',
                onPressed: () => Navigator.pop(context),
                icon: const Icon(Icons.close),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text('学习辅助', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 18),
          ShanganSurface(
            borderColor: ShanganColors.blue,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Icon(
                  Icons.timer_outlined,
                  size: 36,
                  color: ShanganColors.blue,
                ),
                const SizedBox(height: 12),
                Text('专注计时', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 6),
                const Text('独立记录专注时长，不作为作战单待办。'),
                const SizedBox(height: 16),
                FilledButton(
                  key: const Key('startFocusWidget'),
                  onPressed: onStartFocus,
                  child: const Text('开始专注'),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}
