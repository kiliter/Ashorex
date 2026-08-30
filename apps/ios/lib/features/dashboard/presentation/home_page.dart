import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/features/dashboard/data/dashboard_repository.dart';

/// 首页展示考试倒计时、进度压力和后续模块逐步接入的学习汇总。
final class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('上岸')),
      body: FutureBuilder<DashboardData>(
        future: ref.read(dashboardRepositoryProvider).load(),
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
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
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        exam.name,
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 12),
                      Text(
                        '距离考试 ${pressure.daysUntilExam} 天',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      const SizedBox(height: 8),
                      Text('距离课程完成目标 ${pressure.daysUntilTarget} 天'),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Card(
                child: ListTile(
                  minTileHeight: 88,
                  leading: Icon(
                    atRisk ? Icons.warning_amber_rounded : Icons.check_circle,
                  ),
                  title: Text(atRisk ? '进度有风险' : '进度正常'),
                  subtitle: Text(
                    '剩余 ${pressure.remainingLessons} 课时 · '
                    '每天至少 ${pressure.requiredDailyPace.toStringAsFixed(2)} 课时\n'
                    '近 7 天每天 ${pressure.actualDailyPace.toStringAsFixed(2)} 课时',
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Card(
                child: Column(
                  children: [
                    ListTile(
                      title: const Text('今日计划'),
                      trailing: Text(dashboard.todayPlanStatus),
                      onTap: () => context.push('/plan'),
                    ),
                    ListTile(
                      title: const Text('历史欠债'),
                      trailing: Text('${dashboard.openDebtSeconds ~/ 60} 分钟'),
                      onTap: () => context.push('/debts'),
                    ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
