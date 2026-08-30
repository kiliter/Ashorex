import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/features/reporting/data/report_repository.dart';

/// 数据 Tab 的日报首页，同时承载固定模板生成的晚间审判。
final class DailyReportPage extends ConsumerStatefulWidget {
  const DailyReportPage({this.initialDate, this.showAppBar = false, super.key});

  final DateTime? initialDate;
  final bool showAppBar;

  @override
  ConsumerState<DailyReportPage> createState() => _DailyReportPageState();
}

final class _DailyReportPageState extends ConsumerState<DailyReportPage> {
  late DateTime _date;
  late Future<DailyReportData> _report;

  @override
  void initState() {
    super.initState();
    final now = widget.initialDate ?? DateTime.now();
    _date = DateTime(now.year, now.month, now.day);
    _load();
  }

  void _load() => _report = ref.read(reportRepositoryProvider).loadDaily(_date);

  @override
  Widget build(BuildContext context) {
    final content = FutureBuilder<DailyReportData>(
      future: _report,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError || !snapshot.hasData) {
          return _ReportError(onRetry: () => setState(_load));
        }
        return _DailyReportBody(
          report: snapshot.data!,
          previous: () => _moveDate(-1),
          next: () => _moveDate(1),
          openWeekly: () {
            final monday = _date.subtract(Duration(days: _date.weekday - 1));
            context.push('/reports/weekly?weekStart=${_formatDate(monday)}');
          },
        );
      },
    );
    return Scaffold(
      appBar: widget.showAppBar ? AppBar(title: const Text('学习日报')) : null,
      body: SafeArea(child: content),
    );
  }

  void _moveDate(int days) {
    setState(() {
      _date = _date.add(Duration(days: days));
      _load();
    });
  }
}

final class _DailyReportBody extends StatelessWidget {
  const _DailyReportBody({
    required this.report,
    required this.previous,
    required this.next,
    required this.openWeekly,
  });

  final DailyReportData report;
  final VoidCallback previous;
  final VoidCallback next;
  final VoidCallback openWeekly;

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      Row(
        children: [
          Text('数据', style: Theme.of(context).textTheme.headlineMedium),
          const Spacer(),
          TextButton(onPressed: openWeekly, child: const Text('查看周报')),
        ],
      ),
      Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          IconButton(onPressed: previous, icon: const Icon(Icons.chevron_left)),
          Text(
            _formatDate(report.date),
            style: Theme.of(context).textTheme.titleMedium,
          ),
          IconButton(onPressed: next, icon: const Icon(Icons.chevron_right)),
        ],
      ),
      const SizedBox(height: 12),
      Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('晚间审判', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 10),
              Text(
                report.judgmentText,
                style: Theme.of(context).textTheme.bodyLarge,
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 12),
      _MetricGrid(
        metrics: [
          ('计划完成', '${report.completionRate}%'),
          (
            '有效学习',
            _shortDuration(report.videoStudySeconds + report.focusSeconds),
          ),
          ('视频学习', _shortDuration(report.videoStudySeconds)),
          ('专注计时', _shortDuration(report.focusSeconds)),
          ('答题正确率', '${report.answerAccuracy}%'),
          ('完成任务', '${report.completedTasks}/${report.totalTasks}'),
        ],
      ),
      const SizedBox(height: 12),
      Card(
        child: Column(
          children: [
            ListTile(
              title: const Text('新增欠债'),
              trailing: Text(_shortDuration(report.newDebtSeconds)),
            ),
            ListTile(
              title: const Text('偿还欠债'),
              trailing: Text(_shortDuration(report.repaidDebtSeconds)),
            ),
            ListTile(
              title: const Text('当前欠债'),
              trailing: Text(_shortDuration(report.openDebtSeconds)),
            ),
            ListTile(
              title: const Text('验活失败'),
              trailing: Text('${report.aliveCheckFailureCount} 次'),
            ),
          ],
        ),
      ),
    ],
  );
}

final class _MetricGrid extends StatelessWidget {
  const _MetricGrid({required this.metrics});

  final List<(String, String)> metrics;

  @override
  Widget build(BuildContext context) => GridView.count(
    crossAxisCount: 2,
    childAspectRatio: 1.8,
    shrinkWrap: true,
    physics: const NeverScrollableScrollPhysics(),
    children: metrics
        .map(
          (metric) => Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    metric.$2,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  Text(metric.$1),
                ],
              ),
            ),
          ),
        )
        .toList(),
  );
}

final class _ReportError extends StatelessWidget {
  const _ReportError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
    child: FilledButton.icon(
      onPressed: onRetry,
      icon: const Icon(Icons.refresh),
      label: const Text('日报加载失败，点击重试'),
    ),
  );
}

String _formatDate(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}-'
    '${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')}';

String _shortDuration(int seconds) {
  final hours = seconds ~/ 3600;
  final minutes = (seconds % 3600) ~/ 60;
  return hours > 0 ? '$hours 小时 $minutes 分' : '$minutes 分钟';
}
