import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/features/reporting/data/report_repository.dart';

/// 周报页展示逐日趋势和相对上周变化，不在客户端重新聚合业务数据。
final class WeeklyReportPage extends ConsumerStatefulWidget {
  const WeeklyReportPage({required this.initialWeekStart, super.key});

  final DateTime initialWeekStart;

  @override
  ConsumerState<WeeklyReportPage> createState() => _WeeklyReportPageState();
}

final class _WeeklyReportPageState extends ConsumerState<WeeklyReportPage> {
  late DateTime _weekStart;
  late Future<WeeklyReportData> _report;

  @override
  void initState() {
    super.initState();
    _weekStart = widget.initialWeekStart;
    _load();
  }

  void _load() =>
      _report = ref.read(reportRepositoryProvider).loadWeekly(_weekStart);

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('学习周报')),
    body: FutureBuilder<WeeklyReportData>(
      future: _report,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError || !snapshot.hasData) {
          return Center(
            child: FilledButton(
              onPressed: () => setState(_load),
              child: const Text('周报加载失败，点击重试'),
            ),
          );
        }
        final report = snapshot.data!;
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  onPressed: () => _moveWeek(-7),
                  icon: const Icon(Icons.chevron_left),
                ),
                Text(
                  '${_date(report.weekStart)} 起',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                IconButton(
                  onPressed: () => _moveWeek(7),
                  icon: const Icon(Icons.chevron_right),
                ),
              ],
            ),
            Card(
              child: Column(
                children: [
                  ListTile(
                    title: const Text('有效学习'),
                    trailing: Text(
                      _duration(report.totalEffectiveStudySeconds),
                    ),
                  ),
                  ListTile(
                    title: const Text('计划完成率'),
                    trailing: Text('${report.planCompletionRate}%'),
                  ),
                  ListTile(
                    title: const Text('答题正确率'),
                    trailing: Text('${report.answerAccuracy}%'),
                  ),
                  ListTile(
                    title: const Text('较上周学习变化'),
                    trailing: Text(
                      _signedDuration(report.effectiveStudySecondsChange),
                    ),
                  ),
                  ListTile(
                    title: const Text('较上周完成率变化'),
                    trailing: Text(
                      '${_sign(report.planCompletionRateChange)}%',
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            Text('每日趋势', style: Theme.of(context).textTheme.titleLarge),
            ...report.days.map(
              (day) => ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(_date(day.date)),
                subtitle: Text(
                  '完成率 ${day.completionRate}% · 新增欠债 ${_duration(day.newDebtSeconds)}',
                ),
                trailing: Text(_duration(day.effectiveStudySeconds)),
              ),
            ),
          ],
        );
      },
    ),
  );

  void _moveWeek(int days) {
    setState(() {
      _weekStart = _weekStart.add(Duration(days: days));
      _load();
    });
  }
}

String _date(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}-'
    '${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')}';

String _duration(int seconds) =>
    '${seconds ~/ 3600} 小时 ${(seconds % 3600) ~/ 60} 分';

String _signedDuration(int seconds) =>
    '${seconds >= 0 ? '+' : '-'}${_duration(seconds.abs())}';

String _sign(int value) => value >= 0 ? '+$value' : '$value';
