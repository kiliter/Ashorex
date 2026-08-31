import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
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
          return const ShanganLoading('正在生成学习周报');
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
        final maxStudy = report.days.fold<int>(
          1,
          (value, day) => day.effectiveStudySeconds > value
              ? day.effectiveStudySeconds
              : value,
        );
        return ListView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
          children: [
            const ShanganEyebrow('学习周报'),
            const SizedBox(height: 7),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton.outlined(
                  onPressed: () => _moveWeek(-7),
                  icon: const Icon(Icons.chevron_left),
                ),
                Text(
                  '${_date(report.weekStart)} 起',
                  style: shanganNumberStyle(context, fontSize: 15),
                ),
                IconButton.outlined(
                  onPressed: () => _moveWeek(7),
                  icon: const Icon(Icons.chevron_right),
                ),
              ],
            ),
            const SizedBox(height: 18),
            ShanganMetricGrid(
              metrics: [
                (
                  value: _duration(report.totalEffectiveStudySeconds),
                  label: '本周有效学习',
                  tone: ShanganTagTone.info,
                ),
                (
                  value: '${report.planCompletionRate}%',
                  label: '计划完成率',
                  tone: ShanganTagTone.success,
                ),
                (
                  value: '${report.answerAccuracy}%',
                  label: '答题正确率',
                  tone: ShanganTagTone.warning,
                ),
                (
                  value: _signedDuration(report.effectiveStudySecondsChange),
                  label: '较上周时长',
                  tone: ShanganTagTone.risk,
                ),
                (
                  value: '${_sign(report.planCompletionRateChange)}%',
                  label: '较上周完成率',
                  tone: ShanganTagTone.info,
                ),
                (
                  value: _duration(report.newDebtSeconds),
                  label: '本周新增欠债',
                  tone: ShanganTagTone.risk,
                ),
              ],
            ),
            const SizedBox(height: 22),
            Text('每日趋势', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            ShanganSurface(
              child: SizedBox(
                height: 146,
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: report.days.map((day) {
                    final height =
                        18 + 92 * day.effectiveStudySeconds / maxStudy;
                    return Expanded(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          Text(
                            _compactDuration(day.effectiveStudySeconds),
                            style: Theme.of(context).textTheme.labelSmall,
                          ),
                          const SizedBox(height: 4),
                          Container(
                            height: height,
                            margin: const EdgeInsets.symmetric(horizontal: 4),
                            decoration: BoxDecoration(
                              color: ShanganColors.blue,
                              borderRadius: const BorderRadius.vertical(
                                top: Radius.circular(4),
                              ),
                              border: Border.all(color: ShanganColors.ink),
                            ),
                          ),
                          const SizedBox(height: 5),
                          Text(
                            _weekday(day.date.weekday),
                            style: Theme.of(context).textTheme.labelSmall,
                          ),
                        ],
                      ),
                    );
                  }).toList(),
                ),
              ),
            ),
            const SizedBox(height: 20),
            const Divider(color: ShanganColors.ink),
            ...report.days.map(
              (day) => Container(
                padding: const EdgeInsets.symmetric(vertical: 11),
                decoration: const BoxDecoration(
                  border: Border(bottom: BorderSide(color: ShanganColors.rule)),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        _date(day.date),
                        style: shanganNumberStyle(context, fontSize: 12),
                      ),
                    ),
                    Text(
                      '完成 ${day.completionRate}% · 欠债 ${_duration(day.newDebtSeconds)}',
                      style: Theme.of(context).textTheme.bodySmall,
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

String _compactDuration(int seconds) =>
    '${seconds ~/ 3600}:${((seconds % 3600) ~/ 60).toString().padLeft(2, '0')}';

String _weekday(int weekday) =>
    const ['一', '二', '三', '四', '五', '六', '日'][weekday - 1];
