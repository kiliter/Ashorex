import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/reporting/data/report_repository.dart';

/// 切回数据 Tab 或 App 回到前台时递增，驱动日报滚到设备当天。
final dailyReportRefreshListenable = ValueNotifier<int>(0);

void bumpDailyReportRefresh() => dailyReportRefreshListenable.value++;

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
  late DateTime _pinnedToday;
  late Future<DailyReportData> _report;

  @override
  void initState() {
    super.initState();
    _pinnedToday = shanganDeviceToday(widget.initialDate);
    _date = _pinnedToday;
    _load();
    dailyReportRefreshListenable.addListener(_syncTodayIfPinned);
  }

  void _load() => _report = ref.read(reportRepositoryProvider).loadDaily(_date);

  /// 若用户正在看“今天”，跨日后自动滚到新的当天；已选历史日保持不动。
  void _syncTodayIfPinned() {
    if (!mounted || widget.initialDate != null) return;
    final now = shanganDeviceToday();
    setState(() {
      if (shanganSameDay(_date, _pinnedToday)) {
        _date = now;
      }
      _pinnedToday = now;
      _load();
    });
  }

  @override
  void dispose() {
    dailyReportRefreshListenable.removeListener(_syncTodayIfPinned);
    super.dispose();
  }

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
          selectDate: _selectDate,
          goToday: _goToday,
          openWeekly: () {
            final monday = _date.subtract(Duration(days: _date.weekday - 1));
            context.push('/reports/weekly?weekStart=${_formatDate(monday)}');
          },
        );
      },
    );
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.showAppBar ? '学习日报' : '数据'),
        automaticallyImplyLeading: widget.showAppBar,
      ),
      body: content,
    );
  }

  Future<void> _selectDate() async {
    final selected = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(2020),
      lastDate: DateTime.now().add(const Duration(days: 365)),
      helpText: '选择日报日期',
    );
    if (selected == null || !mounted) return;
    setState(() {
      _date = DateTime(selected.year, selected.month, selected.day);
      _load();
    });
  }

  void _goToday() {
    final now = DateTime.now();
    setState(() {
      _date = DateTime(now.year, now.month, now.day);
      _load();
    });
  }
}

final class _DailyReportBody extends StatelessWidget {
  const _DailyReportBody({
    required this.report,
    required this.selectDate,
    required this.goToday,
    required this.openWeekly,
  });

  final DailyReportData report;
  final VoidCallback selectDate;
  final VoidCallback goToday;
  final VoidCallback openWeekly;

  @override
  Widget build(BuildContext context) {
    final judgmentTone = report.abandoned || report.completionRate < 60
        ? ShanganTagTone.risk
        : report.completionRate >= 90
        ? ShanganTagTone.success
        : ShanganTagTone.warning;
    return ListView(
      padding: shanganPagePadding,
      children: [
        ShanganSurface(
          borderColor: ShanganColors.blue,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(child: ShanganEyebrow('学习日报')),
                  ShanganStatusTag(
                    _planStatus(report.planStatus),
                    tone: report.abandoned
                        ? ShanganTagTone.risk
                        : ShanganTagTone.info,
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const Key('selectDailyReportDate'),
                      onPressed: selectDate,
                      icon: const Icon(Icons.calendar_month_outlined),
                      label: Text(
                        _formatDate(report.date),
                        style: shanganNumberStyle(context, fontSize: 14),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  TextButton(onPressed: goToday, child: const Text('回到今天')),
                ],
              ),
              const SizedBox(height: 16),
              ShanganCompletionHero(
                percent: report.completionRate,
                caption: '计划完成率',
              ),
              const SizedBox(height: 14),
              ShanganNotice(
                title: '晚间审判 · 规则生成',
                message: report.judgmentText,
                tone: judgmentTone,
                boxed: true,
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        ShanganSurface(
          borderColor: ShanganColors.blue,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const ShanganEyebrow('当日学习数据'),
              const SizedBox(height: 8),
              ShanganMetricGrid(
                embedded: true,
                metrics: [
                  (
                    value: shanganDuration(
                      report.videoStudySeconds + report.focusSeconds,
                    ),
                    label: '有效学习',
                    tone: ShanganTagTone.success,
                  ),
                  (
                    value: shanganDuration(report.videoStudySeconds),
                    label: '视频学习',
                    tone: ShanganTagTone.warning,
                  ),
                  (
                    value: shanganDuration(report.focusSeconds),
                    label: '专注时长',
                    tone: ShanganTagTone.info,
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
                    value: '${report.aliveCheckFailureCount}',
                    label: '验活失败',
                    tone: ShanganTagTone.warning,
                  ),
                  (
                    value: '${report.mockExamCompletedCount}',
                    label: '完成模拟考试',
                    tone: ShanganTagTone.success,
                  ),
                  (
                    value: '${report.mockExamAwaitingUploadCount}',
                    label: '待传考试试卷',
                    tone: ShanganTagTone.warning,
                  ),
                ],
              ),
            ],
          ),
        ),
        if (report.dayOutcome == 'SLACKED') ...[
          const SizedBox(height: 16),
          const ShanganSurface(
            borderColor: ShanganColors.red,
            child: ShanganNotice(
              title: '今日自动结论：开摆',
              message: '服务端发现今日既没有作战单，也没有可信观看记录。',
              tone: ShanganTagTone.risk,
            ),
          ),
        ],
        if (report.openDebtSeconds > 0 ||
            report.newDebtSeconds > 0 ||
            report.repaidDebtSeconds > 0) ...[
          const SizedBox(height: 16),
          ShanganSurface(
            borderColor: report.openDebtSeconds > 0
                ? ShanganColors.red
                : ShanganColors.blue,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const ShanganEyebrow('学习欠债'),
                const SizedBox(height: 8),
                Text(
                  '当前欠债 ${shanganDuration(report.openDebtSeconds)}',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 4),
                Text(
                  '今日偿还 ${shanganDuration(report.repaidDebtSeconds)}，新增 ${shanganDuration(report.newDebtSeconds)}。',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ],
        const SizedBox(height: 18),
        FilledButton(onPressed: openWeekly, child: const Text('查看本周学习周报')),
      ],
    );
  }
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

String _planStatus(String status) => switch (status) {
  'COMPLETED' => '已完成',
  'ABANDONED' => '已结算',
  'CLOSED_WITH_DEBT' => '欠债结算',
  'ACTIVE' => '进行中',
  'LOCKED' => '进行中',
  _ => status,
};
