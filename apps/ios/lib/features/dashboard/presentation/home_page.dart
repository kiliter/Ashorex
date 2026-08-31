import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';

/// 首页展示考试倒计时、进度压力和后续模块逐步接入的学习汇总。
final class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('首页')),
      body: FutureBuilder<DashboardData>(
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
              ShanganSurface(
                borderColor: ShanganColors.red,
                backgroundColor: ShanganColors.redSoft,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const ShanganEyebrow('今日追赶中', color: ShanganColors.red),
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
              ShanganSurface(
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
                          _planStatusLabel(dashboard.todayPlanStatus),
                          tone: dashboard.todayPlanStatus == 'LOCKED'
                              ? ShanganTagTone.warning
                              : ShanganTagTone.neutral,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '今天的任务，必须有结论',
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      dashboard.todayPlanStatus == 'NONE'
                          ? '今天还没有计划，先选择课程或专注任务。'
                          : '计划状态由服务端锁定；未完成量会准确进入学习欠债。',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: () => context.push('/plan'),
                      child: Text(
                        dashboard.todayPlanStatus == 'LOCKED'
                            ? '继续推进今天'
                            : '查看今日计划',
                      ),
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
                                  style: Theme.of(context).textTheme.titleLarge,
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
                            value: pressure.actualDailyPace.toStringAsFixed(1),
                            label: '近 7 日实际速度',
                            tone: ShanganTagTone.warning,
                          ),
                          (
                            value: shanganDuration(dashboard.studyTodaySeconds),
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
    );
  }
}

String _planStatusLabel(String status) => switch (status) {
  'DRAFT' => '草稿',
  'LOCKED' => '已锁定',
  'COMPLETED' => '已完成',
  'ABANDONED' => '已开摆',
  'CLOSED_WITH_DEBT' => '欠债结算',
  _ => '未创建',
};
