import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';

/// 首页展示学习压力，并通过右滑或显式按钮打开独立小工具菜单。
final class HomePage extends ConsumerStatefulWidget {
  const HomePage({super.key});

  @override
  ConsumerState<HomePage> createState() => _HomePageState();
}

final class _HomePageState extends ConsumerState<HomePage> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();

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
      endDrawer: const _HomeWidgetsDrawer(),
      body: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onHorizontalDragEnd: (details) {
          if ((details.primaryVelocity ?? 0) > 280) _openWidgets();
        },
        child: FutureBuilder<DashboardData>(
          future: ref.read(dashboardRepositoryProvider).load(),
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const ShanganLoading('正在核对今日学习数据');
            }
            if (snapshot.hasError || !snapshot.hasData) {
              return const Center(child: Text('首页加载失败，请稍后重试'));
            }
            final dashboard = snapshot.data!;
            if (dashboard.exam == null) {
              WidgetsBinding.instance.addPostFrameCallback((_) {
                if (context.mounted) context.push('/exam-goal');
              });
              return const Center(child: Text('请先设置考试目标'));
            }
            final exam = dashboard.exam!;
            final pressure = dashboard.progressPressure!;
            final atRisk = pressure.riskStatus == 'AT_RISK';
            final lessonProgress = pressure.totalLessons == 0
                ? 0.0
                : pressure.completedLessons / pressure.totalLessons;
            return ListView(
              padding: shanganPagePadding,
              children: [
                _BattleOrderHomeCard(status: dashboard.todayPlanStatus),
                const SizedBox(height: 22),
                ShanganSurface(
                  borderColor: ShanganColors.red,
                  backgroundColor: ShanganColors.redSoft,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const ShanganEyebrow(
                            '今日追赶中',
                            color: ShanganColors.red,
                          ),
                          const Spacer(),
                          ShanganStatusTag(
                            atRisk ? '进度有风险' : '风险可控',
                            tone: atRisk
                                ? ShanganTagTone.risk
                                : ShanganTagTone.success,
                          ),
                        ],
                      ),
                      const SizedBox(height: 10),
                      Text(
                        atRisk ? '今天的缺口，不留给明天' : '保持节奏，把今天结清',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '当前剩余 ${pressure.remainingLessons} 课时。近 7 日每天 '
                        '${pressure.actualDailyPace.toStringAsFixed(1)} 课时，'
                        '目标要求每天 ${pressure.requiredDailyPace.toStringAsFixed(1)} 课时。',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      const SizedBox(height: 14),
                      ShanganProgress(
                        value: pressure.requiredDailyPace <= 0
                            ? 1
                            : (pressure.actualDailyPace /
                                      pressure.requiredDailyPace)
                                  .clamp(0, 1),
                        color: atRisk ? ShanganColors.red : ShanganColors.green,
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 22),
                ShanganSurface(
                  borderColor: ShanganColors.blue,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(child: ShanganEyebrow(exam.name)),
                          ShanganStatusTag(
                            atRisk ? '追赶' : '正常',
                            tone: atRisk
                                ? ShanganTagTone.warning
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

  void _openWidgets() => _scaffoldKey.currentState?.openEndDrawer();
}

/// 今日作战单是首页首要动作，始终置于压力和考试信息之前。
final class _BattleOrderHomeCard extends StatelessWidget {
  const _BattleOrderHomeCard({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) => ShanganSurface(
    dashed: true,
    borderColor: ShanganColors.ochre,
    backgroundColor: ShanganColors.ochreSoft,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Expanded(child: ShanganEyebrow('今日作战单')),
            ShanganStatusTag(
              _planStatusLabel(status),
              tone: status == 'ACTIVE'
                  ? ShanganTagTone.warning
                  : ShanganTagTone.neutral,
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text('今天先做什么', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 8),
        Text(
          status == 'NONE'
              ? '集中选择今天要完成的课时或模拟考试。'
              : '当天可继续添加、删除未开始的项目；已经开始的记录会保留。',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: () => context.push('/plan'),
          child: Text(status == 'NONE' ? '制定今日作战单' : '修改作战单'),
        ),
      ],
    ),
  );
}

/// 首页小工具独立于作战单，专注计时不会参与每日任务编排。
final class _HomeWidgetsDrawer extends StatelessWidget {
  const _HomeWidgetsDrawer();

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
                  onPressed: () {
                    Navigator.pop(context);
                    context.push(
                      Uri(
                        path: '/focus',
                        queryParameters: const {
                          'title': '专注计时',
                          'plannedSeconds': '1500',
                        },
                      ).toString(),
                    );
                  },
                  child: const Text('开始 25 分钟专注'),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

String _planStatusLabel(String status) => switch (status) {
  'DRAFT' => '草稿',
  'ACTIVE' => '执行中',
  'LOCKED' => '执行中',
  'COMPLETED' => '已完成',
  'ABANDONED' => '已结算',
  'CLOSED_WITH_DEBT' => '欠债结算',
  _ => '未创建',
};
