import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
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
          return const ShanganLoading('正在生成学习日报');
        }
        if (snapshot.hasError || !snapshot.hasData) {
          return _ReportError(
            onRetry: () => setState(() {
              _load();
            }),
          );
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
    padding: shanganPagePadding,
    children: [
      Row(
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const ShanganEyebrow('学习日报'),
              const SizedBox(height: 5),
              Text('数据', style: Theme.of(context).textTheme.headlineMedium),
            ],
          ),
          const Spacer(),
          ShanganStatusTag(
            _planStatus(report.planStatus),
            tone: report.abandoned ? ShanganTagTone.risk : ShanganTagTone.info,
          ),
        ],
      ),
      const SizedBox(height: 14),
      Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          IconButton.outlined(
            onPressed: previous,
            icon: const Icon(Icons.chevron_left),
          ),
          const SizedBox(width: 12),
          Text(
            _formatDate(report.date),
            style: shanganNumberStyle(context, fontSize: 15),
          ),
          const SizedBox(width: 12),
          IconButton.outlined(
            onPressed: next,
            icon: const Icon(Icons.chevron_right),
          ),
        ],
      ),
      const SizedBox(height: 18),
      ShanganNotice(
        title: '晚间审判 · 规则生成',
        message: report.judgmentText,
        tone: report.abandoned || report.completionRate < 60
            ? ShanganTagTone.risk
            : report.completionRate >= 90
            ? ShanganTagTone.success
            : ShanganTagTone.warning,
      ),
      const SizedBox(height: 20),
      ShanganMetricGrid(
        metrics: [
          (
            value: '${report.completionRate}%',
            label: '计划完成率',
            tone: ShanganTagTone.info,
          ),
          (
            value: _shortDuration(
              report.videoStudySeconds + report.focusSeconds,
            ),
            label: '有效学习',
            tone: ShanganTagTone.success,
          ),
          (
            value: _shortDuration(report.videoStudySeconds),
            label: '视频学习',
            tone: ShanganTagTone.warning,
          ),
          (
            value: _shortDuration(report.focusSeconds),
            label: '专注时长',
            tone: ShanganTagTone.risk,
          ),
          (
            value: '${report.completedTasks}/${report.totalTasks}',
            label: '完成任务',
            tone: ShanganTagTone.info,
          ),
          (
            value: '${report.answerAccuracy}%',
            label: '答题正确率',
            tone: ShanganTagTone.success,
          ),
          (
            value: _shortDuration(report.newDebtSeconds),
            label: '新增欠债',
            tone: ShanganTagTone.risk,
          ),
          (
            value: '${report.aliveCheckFailureCount}',
            label: '验活失败',
            tone: ShanganTagTone.warning,
          ),
        ],
      ),
      const SizedBox(height: 18),
      ShanganNotice(
        title: '当前欠债 ${_shortDuration(report.openDebtSeconds)}',
        message:
            '今日偿还 ${_shortDuration(report.repaidDebtSeconds)}，新增 ${_shortDuration(report.newDebtSeconds)}。',
        tone: report.openDebtSeconds > 0
            ? ShanganTagTone.risk
            : ShanganTagTone.success,
      ),
      const SizedBox(height: 18),
      FilledButton(onPressed: openWeekly, child: const Text('查看本周学习周报')),
    ],
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

String _planStatus(String status) => switch (status) {
  'COMPLETED' => '已完成',
  'ABANDONED' => '已开摆',
  'CLOSED_WITH_DEBT' => '欠债结算',
  'LOCKED' => '进行中',
  _ => status,
};
