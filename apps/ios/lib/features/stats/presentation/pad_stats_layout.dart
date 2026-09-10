import 'package:flutter/material.dart';
import 'package:shangan_ios/core/layout/pad_chrome.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// Pad 数据页「计划执行」：最近若干天的完成数，只读已有日汇总。
final class PadExecutionCard extends StatelessWidget {
  const PadExecutionCard({required this.view, required this.range, super.key});

  final StatsView view;
  final HomeRange range;

  @override
  Widget build(BuildContext context) {
    final rows = view.days.length <= 7
        ? view.days
        : view.days.sublist(view.days.length - 7);
    final period = switch (range) {
      HomeRange.day => '今日',
      HomeRange.week => '本周',
      HomeRange.month => '本月',
    };
    final rate = view.totalTodos == 0
        ? 0
        : view.doneTodos * 100 ~/ view.totalTodos;
    // 用统计窗口末日对齐「今天」高亮，避免在界面层直接取设备时钟。
    final today = DateTime(view.end.year, view.end.month, view.end.day);
    return PadSurface(
      padding: const EdgeInsets.fromLTRB(20, 17, 20, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  '计划执行',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
                ),
              ),
              Text(
                '$period已完成 ${view.doneTodos} / ${view.totalTodos} 项',
                style: const TextStyle(
                  fontSize: 9,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
          ),
          const SizedBox(height: 21),
          if (rows.isEmpty)
            const Text(
              '这一段还没有执行记录。',
              style: TextStyle(fontSize: 11, color: ShanganColors.mutedInk),
            )
          else
            Row(
              children: [
                for (var index = 0; index < rows.length; index++) ...[
                  if (index > 0) const SizedBox(width: 9),
                  Expanded(
                    child: _ExecutionDay(day: rows[index], today: today),
                  ),
                ],
              ],
            ),
          const SizedBox(height: 17),
          Text(
            '${range == HomeRange.month ? "图中展示最近 7 天 · " : ""}'
            '$period计划完成率 $rate% · 只统计已有记录，不计未来日期。',
            style: const TextStyle(
              fontSize: 9,
              height: 1.6,
              color: ShanganColors.mutedInk,
            ),
          ),
        ],
      ),
    );
  }
}

final class _ExecutionDay extends StatelessWidget {
  const _ExecutionDay({required this.day, required this.today});

  final StatsDay day;
  final DateTime today;

  @override
  Widget build(BuildContext context) {
    final isToday =
        day.date.year == today.year &&
        day.date.month == today.month &&
        day.date.day == today.day;
    final rate = day.total == 0 ? 0.0 : day.done / day.total;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                isToday ? '今天' : '${day.date.month}/${day.date.day}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 8,
                  color: isToday ? ShanganColors.blue : ShanganColors.mutedInk,
                ),
              ),
            ),
            Text(
              '${(rate * 100).round()}%',
              style: TextStyle(
                fontSize: 8,
                color: isToday ? ShanganColors.blue : ShanganColors.mutedInk,
              ),
            ),
          ],
        ),
        const SizedBox(height: 9),
        Text.rich(
          TextSpan(
            text: '${day.done}',
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              letterSpacing: -0.4,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
            children: [
              TextSpan(
                text: ' / ${day.total}',
                style: const TextStyle(
                  fontSize: 9,
                  fontWeight: FontWeight.w500,
                  color: ShanganColors.mutedInk,
                  letterSpacing: 0,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 9),
        ClipRRect(
          borderRadius: BorderRadius.circular(99),
          child: LinearProgressIndicator(
            value: rate,
            minHeight: 4,
            backgroundColor: const Color(0xFFE7EFE9),
            color: ShanganColors.green,
          ),
        ),
      ],
    );
  }
}

/// Pad 数据页「学习构成」：观看与专注时长占比，数字来自同一份 StatsView。
final class PadCompositionCard extends StatelessWidget {
  const PadCompositionCard({
    required this.view,
    required this.range,
    super.key,
  });

  final StatsView view;
  final HomeRange range;

  @override
  Widget build(BuildContext context) {
    final total = view.totalMs;
    final watchShare = total == 0 ? 0.0 : view.watchedMs / total;
    final period = switch (range) {
      HomeRange.day => '今日',
      HomeRange.week => '本周',
      HomeRange.month => '本月',
    };
    final hours = (total / 3600000);
    return PadSurface(
      padding: const EdgeInsets.fromLTRB(20, 17, 20, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  '学习构成',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
                ),
              ),
              Text(
                '$period学习时长',
                style: const TextStyle(
                  fontSize: 9,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              PadProgressDial(
                progress: watchShare,
                size: 94,
                strokeWidth: 10,
                color: ShanganColors.blue,
                trackColor: const Color(0xFFAC955E),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      '${hours.toStringAsFixed(1)}h',
                      style: const TextStyle(
                        fontSize: 19,
                        height: 1,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.7,
                        fontFeatures: [FontFeature.tabularFigures()],
                      ),
                    ),
                    const SizedBox(height: 5),
                    const Text(
                      '累计学习',
                      style: TextStyle(
                        fontSize: 8,
                        color: ShanganColors.mutedInk,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 19),
              Expanded(
                child: Column(
                  children: [
                    _ShareLine(
                      label: '课程观看',
                      percent: (watchShare * 100).round(),
                      value: formatPadMetricDuration(view.watchedMs),
                      color: ShanganColors.blue,
                    ),
                    const SizedBox(height: 15),
                    _ShareLine(
                      label: '专注计时',
                      percent: 100 - (watchShare * 100).round(),
                      value: formatPadMetricDuration(view.focusedMs),
                      color: const Color(0xFFAC955E),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

final class _ShareLine extends StatelessWidget {
  const _ShareLine({
    required this.label,
    required this.percent,
    required this.value,
    required this.color,
  });

  final String label;
  final int percent;
  final String value;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              width: 5,
              height: 5,
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                label,
                style: const TextStyle(
                  fontSize: 9,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ),
            Text(
              '$percent%',
              style: const TextStyle(
                fontSize: 9,
                color: ShanganColors.mutedInk,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          value,
          style: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            letterSpacing: -0.3,
            fontFeatures: [FontFeature.tabularFigures()],
          ),
        ),
      ],
    );
  }
}
