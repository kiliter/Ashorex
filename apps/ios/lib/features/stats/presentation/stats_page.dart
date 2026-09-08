import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// 数据 Tab：日 / 周 / 月统计（原型 5-1 ~ 5-3）。
///
/// 只统计三类 Todo 产生的真实量：观看时长与进度、专注时长与完成率、待办完成数。
final class StatsPage extends ConsumerWidget {
  const StatsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final range = ref.watch(statsRangeProvider);
    final date = ref.watch(statsDateProvider);
    final stats = ref.watch(statsProvider);
    final title = switch (range) {
      HomeRange.day => '学习数据',
      HomeRange.week => '本周',
      HomeRange.month => '${date.month} 月',
    };
    // 标题和周期固定，只有统计正文参与滚动。
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 0),
      child: Column(
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'STATS',
                      style: TextStyle(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 1.6,
                        color: ShanganColors.mutedInk,
                      ),
                    ),
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -0.8,
                      ),
                    ),
                  ],
                ),
              ),
              ShanganIconButton(
                icon: Icons.calendar_today_outlined,
                semanticLabel: '选择统计日期',
                onTap: () => _pickDate(context, ref, date),
              ),
            ],
          ),
          const SizedBox(height: 12),
          ShanganSegmented(
            labels: const ['日', '周', '月'],
            selectedIndex: range.index,
            onChanged: (index) => ref
                .read(statsRangeProvider.notifier)
                .select(HomeRange.values[index]),
          ),
          const SizedBox(height: 12),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () async => ref.invalidate(statsProvider),
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.only(bottom: 110),
                children: [
                  stats.when(
                    loading: () => const Padding(
                      padding: EdgeInsets.symmetric(vertical: 40),
                      child: Center(child: CircularProgressIndicator()),
                    ),
                    error: (error, _) => Text('统计加载失败：$error'),
                    data: (view) => _StatsBody(view: view, range: range),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _pickDate(
    BuildContext context,
    WidgetRef ref,
    DateTime current,
  ) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: current,
      firstDate: DateTime(current.year - 2),
      lastDate: DateTime(current.year + 2),
    );
    if (picked != null) {
      ref.read(statsDateProvider.notifier).select(picked);
    }
  }
}

final class _StatsBody extends StatefulWidget {
  const _StatsBody({required this.view, required this.range});

  final StatsView view;
  final HomeRange range;

  @override
  State<_StatsBody> createState() => _StatsBodyState();
}

class _StatsBodyState extends State<_StatsBody> {
  /// 原型 5-2「课程排行」右侧的「按讲师」切换。
  bool _rankByPerson = false;

  @override
  Widget build(BuildContext context) {
    final view = widget.view;
    final range = widget.range;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StatStrip(items: _statItems(view, range)),
        if (range == HomeRange.day && view.hours.isNotEmpty) ...[
          const SizedBox(height: 12),
          _ChartCard(
            title: '今日时段分布',
            unit: '分钟',
            chart: ShanganBarChart(
              columns: view.hours
                  .map(
                    (hour) => ShanganBarColumn(
                      value: (hour.watchedMs + hour.focusedMs) / 60000,
                      caption: '${(hour.watchedMs + hour.focusedMs) ~/ 60000}',
                      label: '${hour.hour}',
                      color: hour.focusedMs > hour.watchedMs
                          ? ShanganColors.ochre
                          : ShanganColors.blue,
                    ),
                  )
                  .toList(growable: false),
            ),
            legend: const ShanganLegend(
              items: [
                ShanganLegendItem(color: ShanganColors.blue, label: '课程观看'),
                ShanganLegendItem(color: ShanganColors.ochre, label: '专注计时'),
              ],
            ),
          ),
        ],
        if (range == HomeRange.week && view.days.isNotEmpty) ...[
          const SizedBox(height: 12),
          _ChartCard(
            title: '每日时长',
            unit: '小时',
            chart: ShanganBarChart(
              columns: view.days
                  .map(
                    (day) => ShanganBarColumn(
                      value: day.totalMs / 3600000,
                      caption: (day.totalMs / 3600000).toStringAsFixed(1),
                      label: weekdayLabel(day.date),
                      color: day.total > 0 && day.done == 0
                          ? ShanganColors.red
                          : day.totalMs < 3600000
                          ? ShanganHeatColors.l2
                          : ShanganColors.blue,
                    ),
                  )
                  .toList(growable: false),
            ),
          ),
        ],
        if (range == HomeRange.month && view.days.isNotEmpty) ...[
          const SizedBox(height: 12),
          _ChartCard(
            title: '学习热力',
            unit: '一 二 三 四 五 六 日',
            chart: _Heatmap(days: view.days),
            legend: const ShanganLegend(
              items: [
                ShanganLegendItem(color: ShanganColors.inkSoft, label: '无记录'),
                ShanganLegendItem(color: ShanganHeatColors.l2, label: '1 小时内'),
                ShanganLegendItem(
                  color: ShanganHeatColors.l3,
                  label: '1 – 3 小时',
                ),
                ShanganLegendItem(color: ShanganColors.blue, label: '3 小时以上'),
                ShanganLegendItem(
                  color: ShanganColors.redSoft,
                  border: ShanganColors.redLine,
                  label: '有待办零完成',
                ),
              ],
            ),
          ),
        ],
        if (view.courseRanking.isNotEmpty || view.personRanking.isNotEmpty) ...[
          SectionTitle(
            title: _rankByPerson ? '人物排行' : '课程排行',
            trailing: TextButton(
              onPressed: () => setState(() => _rankByPerson = !_rankByPerson),
              child: Text(_rankByPerson ? '按课程' : '按讲师'),
            ),
          ),
          ShanganCard(
            padding: const EdgeInsets.symmetric(horizontal: 13),
            child: _Ranking(
              entries: _rankByPerson ? view.personRanking : view.courseRanking,
            ),
          ),
        ],
        if (view.genreRanking.isNotEmpty) ...[
          const SectionTitle(title: '分类占比'),
          ShanganCard(
            padding: const EdgeInsets.all(14),
            child: _GenreShare(
              entries: view.genreRanking,
              focusedMs: view.focusedMs,
            ),
          ),
        ],
        const SectionTitle(title: '专注计时'),
        ShanganCard(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Text(
                    '完成 ${view.focusFinished} 次 · 未完成 ${view.focusAbandoned} 次',
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const Spacer(),
                  Text(
                    '${view.focusCompletionPercent}%',
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w800,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              TargetProgressBar(
                value: view.focusCompletionPercent / 100,
                color: ShanganColors.ochre,
                height: 7,
              ),
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Divider(height: 1, color: ShanganColors.hair),
              ),
              _KeyValue(
                label: '累计专注',
                value: formatDurationCompact(view.focusedMs),
              ),
              if (view.backfilledTodos > 0) ...[
                const SizedBox(height: 6),
                _KeyValue(
                  label: '补记完成',
                  value: '${view.backfilledTodos} 项 · 不计入时长',
                ),
              ],
            ],
          ),
        ),
        if (view.noteTagCounts.isNotEmpty) ...[
          const SectionTitle(title: '备注标签'),
          ShanganCard(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Wrap(
                  spacing: 7,
                  runSpacing: 7,
                  children: view.noteTagCounts.entries
                      .map(
                        (entry) => ShanganFilterChip(
                          label: '${entry.key.label} ${entry.value}',
                          selected: false,
                          onTap: () {},
                        ),
                      )
                      .toList(growable: false),
                ),
                const SizedBox(height: 10),
                const Text(
                  '来自完成回填时的一键备注标签。',
                  style: TextStyle(
                    fontSize: 11.5,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ),
          ),
        ],
        if (view.deletionCounts.isNotEmpty) ...[
          const SectionTitle(title: '删除原因'),
          ShanganCard(
            padding: const EdgeInsets.all(14),
            child: Wrap(
              spacing: 7,
              runSpacing: 7,
              children: view.deletionCounts.entries
                  .map(
                    (entry) => ShanganBadge(
                      label: '${_reasonLabel(entry.key)} ${entry.value}',
                      tone: entry.key == 'GAVE_UP'
                          ? ShanganBadgeTone.red
                          : ShanganBadgeTone.ink,
                    ),
                  )
                  .toList(growable: false),
            ),
          ),
        ],
      ],
    );
  }

  /// 四格指标随档位换口径：日看时长构成，周看日均与完成率，月看有效天与连续天。
  List<StatStripItem> _statItems(StatsView view, HomeRange range) {
    final lessons = view.courseRanking.fold<int>(
      0,
      (previous, entry) => previous + entry.lessonCount,
    );
    return switch (range) {
      HomeRange.day => [
        StatStripItem(
          value: formatDurationCompact(view.totalMs),
          label: '总时长',
          highlighted: true,
        ),
        StatStripItem(
          value: formatDurationCompact(view.watchedMs),
          label: '观看',
        ),
        StatStripItem(
          value: formatDurationCompact(view.focusedMs),
          label: '专注',
        ),
        StatStripItem(
          value: '${view.doneTodos}/${view.totalTodos}',
          label: '完成',
        ),
      ],
      HomeRange.week => [
        StatStripItem(
          value: formatDurationCompact(view.totalMs),
          label: '本周总时长',
          highlighted: true,
        ),
        StatStripItem(
          value: formatDurationCompact(
            view.days.isEmpty ? 0 : view.totalMs ~/ view.days.length,
          ),
          label: '日均',
        ),
        StatStripItem(value: '$lessons', label: '课时'),
        StatStripItem(
          value: view.totalTodos == 0
              ? '0%'
              : '${view.doneTodos * 100 ~/ view.totalTodos}%',
          label: '完成率',
        ),
      ],
      HomeRange.month => [
        StatStripItem(
          value: formatDurationCompact(view.totalMs),
          label: '本月时长',
          highlighted: true,
        ),
        StatStripItem(
          value: '${view.days.where((day) => day.totalMs > 0).length}',
          label: '有效天',
        ),
        StatStripItem(value: '${_streak(view.days)}', label: '连续天'),
        StatStripItem(value: '$lessons', label: '课时'),
      ],
    };
  }

  /// 从最后一天往前数连续有学习记录的天数。
  int _streak(List<StatsDay> days) {
    var streak = 0;
    for (final day in days.reversed) {
      if (day.totalMs > 0) {
        streak++;
      } else if (streak > 0) {
        break;
      }
    }
    return streak;
  }

  String _reasonLabel(String wire) {
    for (final tag in DeletionReasonTag.values) {
      if (tag.wire == wire) return tag.label;
    }
    return wire;
  }
}

/// 原型 5-1 / 5-2 / 5-3 的图表卡：标题 + 右侧单位 + 图表 + 可选图例。
final class _ChartCard extends StatelessWidget {
  const _ChartCard({
    required this.title,
    required this.unit,
    required this.chart,
    this.legend,
  });

  final String title;
  final String unit;
  final Widget chart;
  final Widget? legend;

  @override
  Widget build(BuildContext context) {
    return ShanganCard(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const Spacer(),
              Text(
                unit,
                style: const TextStyle(
                  fontSize: 11,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          chart,
          if (legend != null) ...[const SizedBox(height: 10), legend!],
        ],
      ),
    );
  }
}

/// 月度热力图；时长分 3 档（`<1h` / `1–3h` / `>3h`），「有待办零完成」单独用红底
/// 表达，无记录为灰底，共 5 种状态（原型 5-3、规范 §9、ADR-0035）。
final class _Heatmap extends StatelessWidget {
  const _Heatmap({required this.days});

  final List<StatsDay> days;

  @override
  Widget build(BuildContext context) {
    if (days.isEmpty) return const SizedBox.shrink();
    final leading = days.first.date.weekday - 1;
    return GridView.count(
      crossAxisCount: 7,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: 5,
      crossAxisSpacing: 5,
      children: [
        for (var index = 0; index < leading; index++) const SizedBox.shrink(),
        for (final day in days) _HeatCell(day: day),
      ],
    );
  }
}

final class _HeatCell extends StatelessWidget {
  const _HeatCell({required this.day});

  final StatsDay day;

  @override
  Widget build(BuildContext context) {
    final (Color color, Color? border) = _colorFor(day);
    // 原型热力格只有色块；日期与时长通过语义标签保留给读屏用户。
    return Semantics(
      label:
          '${day.date.month} 月 ${day.date.day} 日 · '
          '${formatDurationCompact(day.totalMs)} · '
          '完成 ${day.done}/${day.total}',
      child: Container(
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(5),
          border: border == null ? null : Border.all(color: border),
        ),
      ),
    );
  }

  /// 分档取自冻结规范 §9「热力图分档」：无 Todo / 有 Todo 零完成（警示）/
  /// `<1h` / `1–3h` / `>3h`，共三档时长，色值对应原型 5-3 图例。
  (Color, Color?) _colorFor(StatsDay day) {
    if (day.total > 0 && day.done == 0) {
      return (ShanganColors.redSoft, ShanganColors.redLine);
    }
    if (day.totalMs == 0) return (ShanganColors.inkSoft, null);
    if (day.totalMs < 3600000) return (ShanganHeatColors.l2, null);
    if (day.totalMs <= 3 * 3600000) return (ShanganHeatColors.l3, null);
    return (ShanganHeatColors.l4, null);
  }
}

final class _Ranking extends StatelessWidget {
  const _Ranking({required this.entries});

  final List<RankingEntry> entries;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        for (var index = 0; index < entries.length; index++) ...[
          if (index > 0) const Divider(height: 1, color: ShanganColors.hair),
          ShanganRankRow(
            leading: ShanganRankNumber(rank: index),
            title: entries[index].name,
            subtitle: entries[index].lessonCount > 0
                ? '${entries[index].lessonCount} 课时'
                : '暂无课时记录',
            value: formatDurationCompact(entries[index].watchedMs),
          ),
        ],
      ],
    );
  }
}

/// 原型 5-3「分类占比」：流派时长占比，末行补上专注 / 待办。
final class _GenreShare extends StatelessWidget {
  const _GenreShare({required this.entries, required this.focusedMs});

  final List<RankingEntry> entries;
  final int focusedMs;

  @override
  Widget build(BuildContext context) {
    final total =
        entries.fold<int>(0, (sum, entry) => sum + entry.watchedMs) + focusedMs;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final entry in entries)
          _ShareRow(
            label: entry.name,
            valueMs: entry.watchedMs,
            total: total,
            color: ShanganColors.blue,
          ),
        if (focusedMs > 0)
          _ShareRow(
            label: '专注 / 待办',
            valueMs: focusedMs,
            total: total,
            color: ShanganColors.ochre,
          ),
      ],
    );
  }
}

final class _ShareRow extends StatelessWidget {
  const _ShareRow({
    required this.label,
    required this.valueMs,
    required this.total,
    required this.color,
  });

  final String label;
  final int valueMs;
  final int total;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              Text(
                '${formatDurationCompact(valueMs)} · '
                '${total == 0 ? 0 : valueMs * 100 ~/ total}%',
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          TargetProgressBar(
            value: total == 0 ? 0 : valueMs / total,
            color: color,
          ),
        ],
      ),
    );
  }
}

/// 原型 `.kv`：左侧固定宽度标签 + 右侧值。
final class _KeyValue extends StatelessWidget {
  const _KeyValue({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(
          width: 74,
          child: Text(
            label,
            style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(
              fontSize: 11.5,
              color: ShanganColors.mutedInk,
            ),
          ),
        ),
      ],
    );
  }
}
