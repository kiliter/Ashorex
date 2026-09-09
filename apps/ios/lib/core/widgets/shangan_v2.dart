import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// 把毫秒格式化为「1:48」或「39 分」这类紧凑时长，用于高密度列表。
String formatDurationCompact(int milliseconds) {
  final totalMinutes = (milliseconds / 60000).floor();
  if (totalMinutes < 60) return '$totalMinutes 分';
  final hours = totalMinutes ~/ 60;
  final minutes = totalMinutes % 60;
  return '$hours:${minutes.toString().padLeft(2, '0')}';
}

/// 把毫秒格式化为「18:40」形式的播放位置。
String formatPosition(int milliseconds) {
  final totalSeconds = (milliseconds / 1000).floor();
  final minutes = totalSeconds ~/ 60;
  final seconds = totalSeconds % 60;
  if (minutes < 60) {
    return '$minutes:${seconds.toString().padLeft(2, '0')}';
  }
  final hours = minutes ~/ 60;
  return '$hours:${(minutes % 60).toString().padLeft(2, '0')}:'
      '${seconds.toString().padLeft(2, '0')}';
}

String formatDate(DateTime date) => '${date.month} 月 ${date.day} 日';

String formatIsoDate(DateTime date) =>
    '${date.year.toString().padLeft(4, '0')}-'
    '${date.month.toString().padLeft(2, '0')}-'
    '${date.day.toString().padLeft(2, '0')}';

const _weekdayLabels = ['一', '二', '三', '四', '五', '六', '日'];

String weekdayLabel(DateTime date) => _weekdayLabels[date.weekday - 1];

/// 类型徽标；不依赖颜色单独表达，同时带文字。
final class TodoTypeBadge extends StatelessWidget {
  const TodoTypeBadge({required this.type, super.key});

  final TodoType type;

  @override
  Widget build(BuildContext context) {
    final (Color foreground, Color background, Color border) = switch (type) {
      TodoType.course => (
        ShanganColors.course,
        ShanganColors.blueSoft,
        ShanganColors.blueLine,
      ),
      TodoType.focus => (
        ShanganColors.ochre,
        ShanganColors.ochreSoft,
        ShanganColors.ochreLine,
      ),
      TodoType.task => (
        ShanganColors.green,
        ShanganColors.greenSoft,
        ShanganColors.greenLine,
      ),
    };
    return _Chip(
      label: type.label,
      foreground: foreground,
      background: background,
      border: border,
    );
  }
}

/// 通用小徽标。
final class ShanganBadge extends StatelessWidget {
  const ShanganBadge({
    required this.label,
    this.tone = ShanganBadgeTone.ink,
    super.key,
  });

  final String label;
  final ShanganBadgeTone tone;

  @override
  Widget build(BuildContext context) {
    final (Color foreground, Color background, Color border) = switch (tone) {
      ShanganBadgeTone.ink => (
        ShanganColors.ink,
        ShanganColors.inkSoft,
        ShanganColors.inkLine,
      ),
      ShanganBadgeTone.blue => (
        ShanganColors.blue,
        ShanganColors.blueSoft,
        ShanganColors.blueLine,
      ),
      ShanganBadgeTone.red => (
        ShanganColors.red,
        ShanganColors.redSoft,
        ShanganColors.redLine,
      ),
      ShanganBadgeTone.green => (
        ShanganColors.green,
        ShanganColors.greenSoft,
        ShanganColors.greenLine,
      ),
      ShanganBadgeTone.ochre => (
        ShanganColors.ochre,
        ShanganColors.ochreSoft,
        ShanganColors.ochreLine,
      ),
    };
    return _Chip(
      label: label,
      foreground: foreground,
      background: background,
      border: border,
    );
  }
}

enum ShanganBadgeTone { ink, blue, red, green, ochre }

final class _Chip extends StatelessWidget {
  const _Chip({
    required this.label,
    required this.foreground,
    required this.background,
    required this.border,
  });

  final String label;
  final Color foreground;
  final Color background;
  final Color border;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: border),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: foreground,
          fontSize: 10.5,
          fontWeight: FontWeight.w700,
          height: 1.5,
        ),
      ),
    );
  }
}

/// 目标看板：主目标大号倒计时 + 其余目标两列紧凑格，一屏展示全部。
final class GoalBoard extends StatelessWidget {
  const GoalBoard({required this.goals, this.onManage, super.key});

  final List<ExamGoal> goals;
  final VoidCallback? onManage;

  @override
  Widget build(BuildContext context) {
    if (goals.isEmpty) {
      return _EmptyGoalBoard(onManage: onManage);
    }
    final primary = goals.firstWhere(
      (goal) => goal.primary,
      orElse: () => goals.first,
    );
    final others = goals.where((goal) => goal.id != primary.id).toList();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: ShanganColors.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: ShanganColors.blue, width: 1.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  '我的目标 · ${goals.length} 个',
                  style: const TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                    letterSpacing: 0.6,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ),
              if (onManage != null)
                GestureDetector(
                  onTap: onManage,
                  child: const Text(
                    '管理',
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                      color: ShanganColors.blue,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 9),
          _PrimaryGoalRow(goal: primary),
          if (others.isNotEmpty) const SizedBox(height: 10),
          if (others.isNotEmpty)
            LayoutBuilder(
              builder: (context, constraints) {
                const spacing = 7.0;
                final width = (constraints.maxWidth - spacing) / 2;
                return Wrap(
                  spacing: spacing,
                  runSpacing: spacing,
                  children: others
                      .map(
                        (goal) => SizedBox(
                          width: width,
                          child: _MiniGoalTile(goal: goal),
                        ),
                      )
                      .toList(growable: false),
                );
              },
            ),
        ],
      ),
    );
  }
}

final class _EmptyGoalBoard extends StatelessWidget {
  const _EmptyGoalBoard({this.onManage});

  final VoidCallback? onManage;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onManage,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: ShanganColors.surface,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: ShanganColors.rule, width: 1.5),
        ),
        child: const Row(
          children: [
            Icon(Icons.flag_outlined, color: ShanganColors.blue),
            SizedBox(width: 10),
            Expanded(
              child: Text(
                '还没有考试目标，点这里新建一个倒计时',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
              ),
            ),
            Icon(Icons.chevron_right, color: ShanganColors.mutedInk),
          ],
        ),
      ),
    );
  }
}

final class _PrimaryGoalRow extends StatelessWidget {
  const _PrimaryGoalRow({required this.goal});

  final ExamGoal goal;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          constraints: const BoxConstraints(minWidth: 66),
          // 原型 `.gb-lead .gb-days{padding:6px 4px}`（1-1 目标看板主目标块）。
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
          decoration: BoxDecoration(
            color: ShanganColors.blueSoft,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: ShanganColors.course),
          ),
          child: Column(
            children: [
              Text(
                '${goal.daysRemaining}',
                style: const TextStyle(
                  fontSize: 26,
                  height: 1,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -1.2,
                  color: ShanganColors.course,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
              const Text(
                '天',
                style: TextStyle(
                  fontSize: 9.5,
                  fontWeight: FontWeight.w700,
                  color: ShanganColors.course,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 11),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 7,
                    height: 7,
                    decoration: const BoxDecoration(
                      color: ShanganColors.red,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      goal.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 15.5,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 2),
              Text(
                '${formatIsoDate(goal.examDate)} 周${weekdayLabel(goal.examDate)} · 主目标',
                style: const TextStyle(
                  fontSize: 11.5,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

final class _MiniGoalTile extends StatelessWidget {
  const _MiniGoalTile({required this.goal});

  final ExamGoal goal;

  @override
  Widget build(BuildContext context) {
    final (
      Color background,
      Color border,
      Color number,
    ) = switch (goal.urgency) {
      GoalUrgency.urgent || GoalUrgency.expired => (
        ShanganColors.redSoft,
        ShanganColors.redLine,
        ShanganColors.red,
      ),
      GoalUrgency.soon => (
        ShanganColors.ochreSoft,
        ShanganColors.ochreLine,
        ShanganColors.ochre,
      ),
      GoalUrgency.normal => (
        ShanganColors.surface,
        ShanganColors.hair,
        ShanganColors.ink,
      ),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  goal.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(
                  '${goal.examDate.month.toString().padLeft(2, '0')}-'
                  '${goal.examDate.day.toString().padLeft(2, '0')}',
                  style: const TextStyle(
                    fontSize: 10,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          // 原型 `.gb-mini .d{min-width:30px;text-align:right}`：数字右对齐后
          // 多个目标的天数纵向能对齐成一列（1-1 目标看板）。
          SizedBox(
            width: 30,
            child: Text(
              '${goal.daysRemaining}',
              textAlign: TextAlign.right,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w800,
                letterSpacing: -0.6,
                color: number,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 四格指标条。
final class StatStrip extends StatelessWidget {
  const StatStrip({required this.items, super.key});

  final List<StatStripItem> items;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        for (var index = 0; index < items.length; index++) ...[
          if (index > 0) const SizedBox(width: 8),
          Expanded(child: _StatTile(item: items[index])),
        ],
      ],
    );
  }
}

final class StatStripItem {
  const StatStripItem({
    required this.value,
    required this.label,
    this.highlighted = false,
    this.highlightTone = ShanganBadgeTone.blue,
  });

  final String value;
  final String label;
  final bool highlighted;

  /// 高亮格的底色档位；督学端首格用赭色（原型 9-2、9-5）。
  final ShanganBadgeTone highlightTone;
}

final class _StatTile extends StatelessWidget {
  const _StatTile({required this.item});

  final StatStripItem item;

  @override
  Widget build(BuildContext context) {
    final (Color background, Color border) = switch (item.highlightTone) {
      ShanganBadgeTone.ochre => (
        ShanganColors.ochreSoft,
        ShanganColors.ochreLine,
      ),
      ShanganBadgeTone.green => (
        ShanganColors.greenSoft,
        ShanganColors.greenLine,
      ),
      ShanganBadgeTone.red => (ShanganColors.redSoft, ShanganColors.redLine),
      _ => (ShanganColors.blueSoft, ShanganColors.blueLine),
    };
    return Container(
      // 原型 `.stat{padding:9px 8px}`（1-1 四格指标、5-1 ~ 5-3、9-2 督学端）。
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 9),
      decoration: BoxDecoration(
        color: item.highlighted ? background : ShanganColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: item.highlighted ? border : ShanganColors.hair,
        ),
      ),
      child: Column(
        children: [
          Text(
            item.value,
            maxLines: 1,
            style: const TextStyle(
              fontSize: 17,
              height: 1.15,
              fontWeight: FontWeight.w800,
              letterSpacing: -0.4,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(height: 3),
          Text(
            item.label,
            maxLines: 1,
            style: const TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w600,
              color: ShanganColors.mutedInk,
            ),
          ),
        ],
      ),
    );
  }
}

/// 课程实际进度条；只有中间目标显示刻度，100% 与轨道终点重合时不重复绘制。
final class TargetProgressBar extends StatelessWidget {
  const TargetProgressBar({
    required this.value,
    this.targetValue,
    this.color = ShanganColors.blue,
    this.height = 7,
    super.key,
  });

  final double value;
  final double? targetValue;
  final Color color;
  final double height;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final target = targetValue;
        // 末端自身即 100%，避免额外竖线被误认为第二段已播放进度。
        final showTarget = target != null && target > 0 && target < 1;
        return SizedBox(
          width: width,
          height: height + 4,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              Positioned(
                top: 2,
                left: 0,
                right: 0,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(99),
                  child: Container(
                    height: height,
                    // 完整轨道独立于填充绘制，零值或极低进度也不呈现为孤立圆点。
                    decoration: BoxDecoration(
                      color: ShanganColors.progressTrack,
                      borderRadius: BorderRadius.circular(99),
                      border: Border.all(color: ShanganColors.progressOutline),
                    ),
                    alignment: Alignment.centerLeft,
                    child: FractionallySizedBox(
                      widthFactor: value.clamp(0.0, 1.0),
                      child: Container(color: color),
                    ),
                  ),
                ),
              ),
              if (showTarget && width >= 2)
                Positioned(
                  left: (width * target - 1).clamp(0.0, width - 2),
                  top: 0,
                  child: Container(
                    width: 2,
                    height: height + 4,
                    decoration: BoxDecoration(
                      color: color,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

/// 日 / 周 / 月分段切换。
final class ShanganSegmented extends StatelessWidget {
  const ShanganSegmented({
    required this.labels,
    required this.selectedIndex,
    required this.onChanged,
    super.key,
  });

  final List<String> labels;
  final int selectedIndex;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: ShanganColors.inkSoft,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          for (var index = 0; index < labels.length; index++)
            Expanded(
              child: GestureDetector(
                onTap: () => onChanged(index),
                child: Container(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  decoration: BoxDecoration(
                    color: index == selectedIndex
                        ? ShanganColors.surface
                        : Colors.transparent,
                    borderRadius: BorderRadius.circular(10),
                    // 原型 `.seg button.on` 有一道极轻的投影把选中块抬起（1-4、5-1、4-1）。
                    boxShadow: index == selectedIndex
                        ? const [
                            BoxShadow(
                              color: Color(0x24263B60),
                              blurRadius: 3,
                              offset: Offset(0, 1),
                            ),
                          ]
                        : null,
                  ),
                  child: Text(
                    labels[index],
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: index == selectedIndex
                          ? ShanganColors.ink
                          : ShanganColors.mutedInk,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 可多选的筛选 chip。
final class ShanganFilterChip extends StatelessWidget {
  const ShanganFilterChip({
    required this.label,
    required this.selected,
    required this.onTap,
    this.tone = ShanganBadgeTone.ink,
    super.key,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;
  final ShanganBadgeTone tone;

  @override
  Widget build(BuildContext context) {
    final selectedColor = switch (tone) {
      ShanganBadgeTone.red => ShanganColors.red,
      ShanganBadgeTone.ochre => ShanganColors.ochre,
      ShanganBadgeTone.green => ShanganColors.green,
      _ => ShanganColors.ink,
    };
    return GestureDetector(
      onTap: onTap,
      child: Container(
        constraints: const BoxConstraints(minHeight: 32),
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 6),
        decoration: BoxDecoration(
          color: selected ? selectedColor : ShanganColors.surface,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: selected ? selectedColor : ShanganColors.rule,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: selected ? ShanganColors.surface : ShanganColors.ink,
          ),
        ),
      ),
    );
  }
}

/// 卡片容器，统一圆角与描边。
final class ShanganCard extends StatelessWidget {
  const ShanganCard({
    required this.child,
    this.padding = const EdgeInsets.symmetric(horizontal: 13, vertical: 2),
    this.borderColor,
    this.backgroundColor,
    super.key,
  });

  final Widget child;
  final EdgeInsets padding;
  final Color? borderColor;
  final Color? backgroundColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        color: backgroundColor ?? ShanganColors.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: borderColor ?? ShanganColors.rule,
          width: 1.5,
        ),
      ),
      child: child,
    );
  }
}

/// 分组小标题。
final class SectionTitle extends StatelessWidget {
  const SectionTitle({
    required this.title,
    this.trailing,
    this.count,
    super.key,
  });

  final String title;
  final Widget? trailing;
  final String? count;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(
            title,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w800),
          ),
          if (count != null) ...[
            const SizedBox(width: 6),
            Text(
              count!,
              style: const TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w700,
                color: ShanganColors.mutedInk,
              ),
            ),
          ],
          const Spacer(),
          ?trailing,
        ],
      ),
    );
  }
}

/// 原型 `.heat i.l1 ~ .l4` 与月视图完成度色阶。
///
/// 这些颜色只服务于热力图与月历格，不属于 `:root` 语义令牌，
/// 因此留在组件层而不进 `ShanganColors`。
abstract final class ShanganHeatColors {
  static const l1 = Color(0xFFD6E3F4);
  static const l2 = Color(0xFF9DBDE6);
  static const l3 = Color(0xFF5A8FD1);
  static const l4 = ShanganColors.blue;
}

/// 原型 `.tip` 内正文使用的深赭色，仅用于赭色提示条文字。
const shanganTipText = Color(0xFF6E5824);

/// 原型 `.grouplabel`：11.5px / w800 / letterSpacing .6 的分组小标签。
///
/// 对应 1-4「快捷跳转」、1-8「逾期 3 天以上」、3-3「附件 / 备注」等位置。
final class ShanganGroupLabel extends StatelessWidget {
  const ShanganGroupLabel(
    this.text, {
    this.padding = const EdgeInsets.only(top: 18, bottom: 8),
    super.key,
  });

  final String text;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: padding,
      child: Text(
        text,
        style: const TextStyle(
          fontSize: 11.5,
          fontWeight: FontWeight.w800,
          letterSpacing: 0.6,
          color: ShanganColors.mutedInk,
        ),
      ),
    );
  }
}

/// 原型 `.todo-meta .m`：11.5px muted 文字 + 可选 14px 前置图标。
final class ShanganMeta extends StatelessWidget {
  const ShanganMeta(this.text, {this.icon, this.color, super.key});

  final String text;
  final IconData? icon;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final tone = color ?? ShanganColors.mutedInk;
    final label = Text(text, style: TextStyle(fontSize: 11.5, color: tone));
    if (icon == null) return label;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: tone),
        const SizedBox(width: 3),
        label,
      ],
    );
  }
}

/// 原型 `.tip`：赭色底提示条，用于历史日期、后台计时等说明。
final class ShanganTip extends StatelessWidget {
  const ShanganTip({
    required this.text,
    this.icon = Icons.error_outline,
    super.key,
  });

  final String text;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: ShanganColors.ochreSoft,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: ShanganColors.ochreLine),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: ShanganColors.ochre),
          const SizedBox(width: 7),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontSize: 11.5,
                height: 1.6,
                color: shanganTipText,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 原型 `.iconbtn`：42x42 圆角 14 描边按钮，`on` 态换蓝色描边与蓝底。
///
/// 对应首页右上角日历 / 铃铛、二级页返回键与课程详情搜索键。
final class ShanganIconButton extends StatelessWidget {
  const ShanganIconButton({
    required this.icon,
    required this.onTap,
    this.highlighted = false,
    this.showDot = false,
    this.semanticLabel,
    this.quarterTurns = 0,
    super.key,
  });

  final IconData icon;
  final VoidCallback onTap;
  final bool highlighted;

  /// 右上角红点，对应原型 `.iconbtn .dot`（有待回应催办时出现）。
  final bool showDot;
  final String? semanticLabel;
  final int quarterTurns;

  @override
  Widget build(BuildContext context) {
    Widget glyph = Icon(
      icon,
      size: 20,
      color: highlighted ? ShanganColors.blue : ShanganColors.ink,
    );
    if (quarterTurns != 0) {
      glyph = RotatedBox(quarterTurns: quarterTurns, child: glyph);
    }
    return Semantics(
      button: true,
      label: semanticLabel,
      child: GestureDetector(
        onTap: onTap,
        child: SizedBox(
          width: 44,
          height: 44,
          child: Center(
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Container(
                  width: 42,
                  height: 42,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: highlighted
                        ? ShanganColors.blueSoft
                        : ShanganColors.surface,
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(
                      color: highlighted
                          ? ShanganColors.blue
                          : ShanganColors.rule,
                      width: 1.5,
                    ),
                  ),
                  child: glyph,
                ),
                if (showDot)
                  Positioned(
                    right: -2,
                    top: -2,
                    child: Container(
                      width: 8,
                      height: 8,
                      decoration: const BoxDecoration(
                        color: ShanganColors.red,
                        shape: BoxShape.circle,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 原型 `.act` 的语义色档位。
enum ShanganActTone { course, focus, task, flat }

/// 原型 `.act`：42x42 圆角 13 的行尾操作按钮。
///
/// 对应 1-1 Todo 行右侧播放 / 暂停 / 勾选、1-5 周视图展开箭头与 4-3 课时播放键。
final class ShanganActButton extends StatelessWidget {
  const ShanganActButton({
    required this.icon,
    required this.tone,
    required this.onTap,
    this.quarterTurns = 0,
    this.semanticLabel,
    super.key,
  });

  final IconData icon;
  final ShanganActTone tone;
  final VoidCallback? onTap;
  final int quarterTurns;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final (Color foreground, Color background, Color border) = switch (tone) {
      ShanganActTone.course => (
        ShanganColors.blue,
        ShanganColors.blueSoft,
        ShanganColors.blueLine,
      ),
      ShanganActTone.focus => (
        ShanganColors.ochre,
        ShanganColors.ochreSoft,
        ShanganColors.ochreLine,
      ),
      ShanganActTone.task => (
        ShanganColors.green,
        ShanganColors.greenSoft,
        ShanganColors.greenLine,
      ),
      ShanganActTone.flat => (
        ShanganColors.mutedInk,
        ShanganColors.inkSoft,
        ShanganColors.inkLine,
      ),
    };
    Widget glyph = Icon(icon, size: 20, color: foreground);
    if (quarterTurns != 0) {
      glyph = RotatedBox(quarterTurns: quarterTurns, child: glyph);
    }
    return Semantics(
      button: true,
      label: semanticLabel,
      child: GestureDetector(
        onTap: onTap,
        child: SizedBox(
          width: 44,
          height: 44,
          child: Center(
            child: Container(
              width: 42,
              height: 42,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: background,
                borderRadius: BorderRadius.circular(13),
                border: Border.all(color: border),
              ),
              child: glyph,
            ),
          ),
        ),
      ),
    );
  }
}

/// 原型 `.tick`（含 `.half` / `.done` / 逾期虚线态）。
///
/// 24px 圆形、2.2px 描边并按 Todo 类型着色；进行中填 11px 实心点，
/// 已完成填绿底白勾，历史逾期改虚线描边（1-7、1-8）。
final class ShanganTick extends StatelessWidget {
  const ShanganTick({
    required this.color,
    this.done = false,
    this.half = false,
    this.dashed = false,
    this.onTap,
    this.semanticLabel,
    super.key,
  });

  final Color color;
  final bool done;
  final bool half;
  final bool dashed;
  final VoidCallback? onTap;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    Widget circle;
    if (done) {
      circle = Container(
        width: 24,
        height: 24,
        decoration: const BoxDecoration(
          color: ShanganColors.green,
          shape: BoxShape.circle,
        ),
        // 原型 `.tick.done .icon{width:13px;height:13px;stroke-width:3}`。
        child: const Icon(Icons.check, size: 13, color: Colors.white),
      );
    } else if (dashed) {
      circle = CustomPaint(
        size: const Size.square(24),
        painter: _DashedCirclePainter(color: color, strokeWidth: 2.2),
      );
    } else {
      circle = Container(
        width: 24,
        height: 24,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: Border.all(color: color, width: 2.2),
        ),
        child: half
            ? Container(
                width: 11,
                height: 11,
                decoration: BoxDecoration(color: color, shape: BoxShape.circle),
              )
            : null,
      );
    }
    if (onTap == null) return circle;
    return Semantics(
      button: true,
      label: semanticLabel,
      child: GestureDetector(onTap: onTap, child: circle),
    );
  }
}

/// 逾期 tick 的虚线圆环绘制。
final class _DashedCirclePainter extends CustomPainter {
  const _DashedCirclePainter({required this.color, required this.strokeWidth});

  final Color color;
  final double strokeWidth;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth;
    final radius = (size.width - strokeWidth) / 2;
    final center = Offset(size.width / 2, size.height / 2);
    const dash = 0.42; // 弧度：约 3.2px 实线
    const gap = 0.34; // 弧度：约 2.6px 空隙
    var start = -math.pi / 2;
    while (start < 3 * math.pi / 2) {
      canvas.drawArc(
        Rect.fromCircle(center: center, radius: radius),
        start,
        dash,
        false,
        paint,
      );
      start += dash + gap;
    }
  }

  @override
  bool shouldRepaint(_DashedCirclePainter oldDelegate) =>
      oldDelegate.color != color || oldDelegate.strokeWidth != strokeWidth;
}

/// 原型 `.pick .box`：22px 方形、圆角 6、2px 描边的复选框，选中填蓝底白钩。
///
/// 对应 1-2 首页编辑态、1-8 未完成汇总编辑态与 2-2 选课时。
final class ShanganCheckBox extends StatelessWidget {
  const ShanganCheckBox({
    required this.checked,
    required this.onToggle,
    this.semanticLabel,
    super.key,
  });

  final bool checked;
  final ValueChanged<bool>? onToggle;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      checked: checked,
      label: semanticLabel,
      child: GestureDetector(
        onTap: () => onToggle?.call(!checked),
        child: Container(
          width: 22,
          height: 22,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: checked ? ShanganColors.blue : Colors.transparent,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(
              color: checked ? ShanganColors.blue : ShanganColors.rule,
              width: 2,
            ),
          ),
          child: checked
              ? const Icon(Icons.check, size: 13, color: Colors.white)
              : null,
        ),
      ),
    );
  }
}

/// 原型 `.pick`：22px 方形复选框 + 标题副标题 + 拖拽把手的选择行。
///
/// 对应 1-2 首页编辑态、2-1 选课程与 2-2 选课时。
final class ShanganPickRow extends StatelessWidget {
  const ShanganPickRow({
    required this.title,
    required this.subtitle,
    this.checked,
    this.onToggle,
    this.leading,
    this.trailing,
    this.extra,
    this.onTap,
    super.key,
  });

  final String title;
  final String subtitle;

  /// 为 null 表示这一行不是复选行（例如 2-1 选课程只做单选进入下一步）。
  final bool? checked;
  final ValueChanged<bool>? onToggle;
  final Widget? leading;
  final Widget? trailing;

  /// 标题下方的附加内容，例如 2-1 的学习进度条。
  final Widget? extra;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final box = checked == null
        ? null
        : ShanganCheckBox(checked: checked!, onToggle: onToggle);
    return InkWell(
      onTap:
          onTap ?? (checked == null ? null : () => onToggle?.call(!checked!)),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 11),
        child: Row(
          children: [
            if (box != null) ...[box, const SizedBox(width: 11)],
            if (leading != null) ...[leading!, const SizedBox(width: 11)],
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Text(
                    subtitle,
                    style: const TextStyle(
                      fontSize: 11.5,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                  if (extra != null) ...[const SizedBox(height: 6), extra!],
                ],
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: 8), trailing!],
          ],
        ),
      ),
    );
  }
}

/// 原型 `.cover`：课程封面占位块，显示课程首字与标签。
final class ShanganCover extends StatelessWidget {
  const ShanganCover({
    required this.letter,
    required this.caption,
    required this.index,
    this.width = 78,
    this.height = 104,
    super.key,
  });

  final String letter;
  final String caption;

  /// 用课程序号轮转四种色板，对应原型 `.cover` / `.cover.g/.o/.r`。
  final int index;
  final double width;
  final double height;

  @override
  Widget build(BuildContext context) {
    final (Color foreground, Color background, Color border) = switch (index %
        4) {
      0 => (
        ShanganColors.course,
        ShanganColors.blueSoft,
        ShanganColors.blueLine,
      ),
      1 => (
        ShanganColors.green,
        ShanganColors.greenSoft,
        ShanganColors.greenLine,
      ),
      2 => (
        ShanganColors.ochre,
        ShanganColors.ochreSoft,
        ShanganColors.ochreLine,
      ),
      _ => (ShanganColors.red, ShanganColors.redSoft, ShanganColors.redLine),
    };
    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: border),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            letter,
            style: TextStyle(
              fontSize: width >= 70 ? 19 : 15,
              fontWeight: FontWeight.w800,
              letterSpacing: -0.5,
              color: foreground,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            caption,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 9.5,
              fontWeight: FontWeight.w700,
              color: foreground,
            ),
          ),
        ],
      ),
    );
  }
}

/// 原型 `.avatar`：46px 圆形首字头像，用于 4-2 人物列表与 6-1 督学人。
final class ShanganAvatar extends StatelessWidget {
  const ShanganAvatar({
    required this.letter,
    required this.index,
    this.size = 46,
    super.key,
  });

  final String letter;
  final int index;
  final double size;

  @override
  Widget build(BuildContext context) {
    final (Color foreground, Color background, Color border) = switch (index %
        4) {
      0 => (
        ShanganColors.course,
        ShanganColors.blueSoft,
        ShanganColors.blueLine,
      ),
      1 => (
        ShanganColors.green,
        ShanganColors.greenSoft,
        ShanganColors.greenLine,
      ),
      2 => (
        ShanganColors.ochre,
        ShanganColors.ochreSoft,
        ShanganColors.ochreLine,
      ),
      _ => (ShanganColors.ink, ShanganColors.inkSoft, ShanganColors.rule),
    };
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: background,
        shape: BoxShape.circle,
        border: Border.all(color: border),
      ),
      child: Text(
        letter,
        style: TextStyle(
          fontSize: size * 0.35,
          fontWeight: FontWeight.w800,
          color: foreground,
        ),
      ),
    );
  }
}

/// 原型 `.rank`：排行 / 明细行，左侧序号或徽标，右侧数值。
final class ShanganRankRow extends StatelessWidget {
  const ShanganRankRow({
    required this.leading,
    required this.title,
    required this.subtitle,
    required this.value,
    this.valueMuted = false,
    super.key,
  });

  final Widget leading;
  final String title;
  final String subtitle;
  final String value;
  final bool valueMuted;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 9),
      child: Row(
        children: [
          leading,
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 13.5,
                    height: 1.35,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(
                  subtitle,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 11,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Text(
            value,
            style: TextStyle(
              fontSize: valueMuted ? 12 : 13.5,
              fontWeight: valueMuted ? FontWeight.w600 : FontWeight.w800,
              color: valueMuted ? ShanganColors.mutedInk : ShanganColors.ink,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

/// 原型 `.rank .no`：22px 圆角序号块，前三名用墨底反白。
final class ShanganRankNumber extends StatelessWidget {
  const ShanganRankNumber({required this.rank, super.key});

  final int rank;

  @override
  Widget build(BuildContext context) {
    final top = rank <= 2;
    return Container(
      width: 22,
      height: 22,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: top ? ShanganColors.ink : ShanganColors.inkSoft,
        borderRadius: BorderRadius.circular(7),
      ),
      child: Text(
        '${rank + 1}',
        style: TextStyle(
          fontSize: 11.5,
          fontWeight: FontWeight.w800,
          color: top ? ShanganColors.surface : ShanganColors.mutedInk,
        ),
      ),
    );
  }
}

/// 原型 5-1 / 5-2 的柱状图：一列一柱 + 顶部数值 + 底部标签。
final class ShanganBarChart extends StatelessWidget {
  const ShanganBarChart({required this.columns, super.key});

  final List<ShanganBarColumn> columns;

  @override
  Widget build(BuildContext context) {
    final maxValue = columns.fold<double>(
      0,
      (previous, column) => column.value > previous ? column.value : previous,
    );
    return SizedBox(
      height: 126,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          for (var index = 0; index < columns.length; index++) ...[
            if (index > 0) const SizedBox(width: 7),
            Expanded(
              child: _ChartColumn(column: columns[index], maxValue: maxValue),
            ),
          ],
        ],
      ),
    );
  }
}

final class ShanganBarColumn {
  const ShanganBarColumn({
    required this.value,
    required this.label,
    required this.caption,
    this.color = ShanganColors.blue,
  });

  final double value;
  final String label;
  final String caption;
  final Color color;
}

final class _ChartColumn extends StatelessWidget {
  const _ChartColumn({required this.column, required this.maxValue});

  final ShanganBarColumn column;
  final double maxValue;

  @override
  Widget build(BuildContext context) {
    // 原型最矮柱固定保留 4% 高度，保证零值也能看出坐标位置。
    final ratio = maxValue <= 0
        ? 0.04
        : (column.value / maxValue).clamp(0.04, 1.0);
    return Column(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        Text(
          column.caption,
          style: const TextStyle(
            fontSize: 9.5,
            color: ShanganColors.mutedInk,
            fontFeatures: [FontFeature.tabularFigures()],
          ),
        ),
        const SizedBox(height: 5),
        Expanded(
          child: FractionallySizedBox(
            alignment: Alignment.bottomCenter,
            heightFactor: ratio,
            child: Container(
              constraints: const BoxConstraints(maxWidth: 26),
              decoration: BoxDecoration(
                color: column.color,
                borderRadius: const BorderRadius.vertical(
                  top: Radius.circular(6),
                  bottom: Radius.circular(3),
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 5),
        Text(
          column.label,
          // 周统计允许星期和日期分两行居中显示。
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 10,
            fontWeight: FontWeight.w600,
            color: ShanganColors.mutedInk,
          ),
        ),
      ],
    );
  }
}

/// 原型 1-4 / 1-6 / 5-3 共用的图例项：色块 + 文案。
final class ShanganLegend extends StatelessWidget {
  const ShanganLegend({required this.items, super.key});

  final List<ShanganLegendItem> items;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 12,
      runSpacing: 6,
      children: items
          .map(
            (item) => Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: item.bar ? 12 : 10,
                  height: item.bar ? 3 : 10,
                  decoration: BoxDecoration(
                    color: item.color,
                    borderRadius: BorderRadius.circular(item.bar ? 9 : 3),
                    border: item.border == null
                        ? null
                        : Border.all(color: item.border!),
                  ),
                ),
                const SizedBox(width: 4),
                Text(
                  item.label,
                  style: const TextStyle(
                    fontSize: 10.5,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ),
          )
          .toList(growable: false),
    );
  }
}

final class ShanganLegendItem {
  const ShanganLegendItem({
    required this.color,
    required this.label,
    this.border,
    this.bar = false,
  });

  final Color color;
  final String label;
  final Color? border;

  /// true 时画成 12x3 的短横条（1-4 最近 7 天图例）。
  final bool bar;
}

/// 单日完成度对应的颜色档位，1-4 / 1-5 / 1-6 共用。
({Color color, String label}) dayOutcomeStyle(DayOutcome outcome) {
  return switch (outcome) {
    DayOutcome.allDone => (color: ShanganColors.green, label: '全部完成'),
    DayOutcome.partial => (color: ShanganColors.blue, label: '部分完成'),
    DayOutcome.noneDone => (color: ShanganColors.red, label: '零完成'),
    DayOutcome.noTodos => (color: ShanganColors.inkSoft, label: '无待办'),
  };
}

/// 原型 1-6 月视图的月历格。
///
/// 每格显示日期与「已完成/总数」，底色按完成度分档；选中日用 2px 蓝描边。
final class ShanganMonthCalendar extends StatelessWidget {
  const ShanganMonthCalendar({
    required this.month,
    required this.days,
    required this.selectedDate,
    required this.onSelect,
    super.key,
  });

  final DateTime month;
  final List<DaySummary> days;
  final DateTime selectedDate;
  final ValueChanged<DateTime> onSelect;

  @override
  Widget build(BuildContext context) {
    final byDay = <int, DaySummary>{
      for (final day in days)
        if (day.date.year == month.year && day.date.month == month.month)
          day.date.day: day,
    };
    final first = DateTime(month.year, month.month, 1);
    final leading = first.weekday - 1;
    final dayCount = DateTime(month.year, month.month + 1, 0).day;
    final cells = <Widget>[
      for (var index = 0; index < leading; index++) const SizedBox.shrink(),
      for (var day = 1; day <= dayCount; day++)
        _MonthCell(
          day: day,
          summary: byDay[day],
          selected:
              selectedDate.year == month.year &&
              selectedDate.month == month.month &&
              selectedDate.day == day,
          onTap: () => onSelect(DateTime(month.year, month.month, day)),
        ),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: _weekdayLabels
              .map(
                (label) => Expanded(
                  child: Text(
                    label,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ),
              )
              .toList(growable: false),
        ),
        const SizedBox(height: 6),
        GridView.count(
          crossAxisCount: 7,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 5,
          crossAxisSpacing: 5,
          children: cells,
        ),
        const SizedBox(height: 10),
        const ShanganLegend(
          items: [
            ShanganLegendItem(color: ShanganColors.green, label: '全完成'),
            ShanganLegendItem(color: ShanganHeatColors.l2, label: '多数完成'),
            ShanganLegendItem(
              color: ShanganColors.redSoft,
              border: ShanganColors.redLine,
              label: '零完成',
            ),
            ShanganLegendItem(color: ShanganColors.inkSoft, label: '无待办'),
          ],
        ),
      ],
    );
  }
}

final class _MonthCell extends StatelessWidget {
  const _MonthCell({
    required this.day,
    required this.summary,
    required this.selected,
    required this.onTap,
  });

  final int day;
  final DaySummary? summary;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    var background = ShanganColors.inkSoft;
    var foreground = ShanganColors.mutedInk;
    Color? border;
    final data = summary;
    if (data != null && data.total > 0) {
      final ratio = data.done / data.total;
      if (data.done == data.total) {
        background = ShanganColors.green;
        foreground = Colors.white;
      } else if (data.done == 0) {
        background = ShanganColors.redSoft;
        border = ShanganColors.redLine;
        foreground = ShanganColors.red;
      } else if (ratio >= 0.7) {
        background = ShanganHeatColors.l2;
        foreground = Colors.white;
      } else {
        background = ShanganHeatColors.l1;
        foreground = ShanganColors.ink;
      }
    }
    if (selected) {
      background = ShanganColors.blueSoft;
      border = ShanganColors.blue;
      foreground = ShanganColors.blue;
    }
    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(8),
          border: border == null
              ? null
              : Border.all(color: border, width: selected ? 2 : 1),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              '$day',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w800,
                color: foreground,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
            if (data != null && data.total > 0)
              Text(
                '${data.done}/${data.total}',
                style: TextStyle(fontSize: 8.5, color: foreground),
              ),
          ],
        ),
      ),
    );
  }
}

/// 原型 1-4：日期头展开的视图切换与跳转面板。
///
/// 内含日 / 周 / 月分段、快捷跳转 pill、最近 7 天完成度条与图例。
final class ShanganDateJumpPanel extends StatelessWidget {
  const ShanganDateJumpPanel({
    required this.rangeIndex,
    required this.onRangeChanged,
    required this.selectedDate,
    required this.onSelectDate,
    required this.recentDays,
    super.key,
  });

  final int rangeIndex;
  final ValueChanged<int> onRangeChanged;
  final DateTime selectedDate;
  final ValueChanged<DateTime> onSelectDate;

  /// 最近 7 天（含选中日）的完成度摘要，缺数据的日子按「无待办」渲染。
  final List<DaySummary> recentDays;

  @override
  Widget build(BuildContext context) {
    final today = DateTime.now();
    final todayDate = DateTime(today.year, today.month, today.day);
    return ShanganCard(
      padding: const EdgeInsets.all(14),
      borderColor: ShanganColors.blue,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ShanganSegmented(
            labels: const ['日', '周', '月'],
            selectedIndex: rangeIndex,
            onChanged: onRangeChanged,
          ),
          const ShanganGroupLabel(
            '快捷跳转',
            padding: EdgeInsets.only(top: 14, bottom: 8),
          ),
          Wrap(
            spacing: 7,
            runSpacing: 7,
            children: [
              ShanganFilterChip(
                label: '今天',
                selected:
                    rangeIndex == 0 && selectedDate.isAtSameMomentAs(todayDate),
                onTap: () {
                  onRangeChanged(0);
                  onSelectDate(todayDate);
                },
              ),
              ShanganFilterChip(
                label: '昨天',
                selected: false,
                onTap: () {
                  onRangeChanged(0);
                  onSelectDate(todayDate.subtract(const Duration(days: 1)));
                },
              ),
              ShanganFilterChip(
                label: '本周',
                selected: rangeIndex == 1,
                onTap: () {
                  onSelectDate(todayDate);
                  onRangeChanged(1);
                },
              ),
              ShanganFilterChip(
                label: '本月',
                selected: rangeIndex == 2,
                onTap: () {
                  onSelectDate(todayDate);
                  onRangeChanged(2);
                },
              ),
            ],
          ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Divider(height: 1, color: ShanganColors.hair),
          ),
          const ShanganGroupLabel(
            '最近 7 天',
            padding: EdgeInsets.only(bottom: 8),
          ),
          Row(
            children: [
              for (var index = 0; index < recentDays.length; index++) ...[
                if (index > 0) const SizedBox(width: 6),
                Expanded(
                  child: _RecentDayTile(
                    summary: recentDays[index],
                    selected: recentDays[index].date.isAtSameMomentAs(
                      selectedDate,
                    ),
                    onTap: () {
                      onRangeChanged(0);
                      onSelectDate(recentDays[index].date);
                    },
                  ),
                ),
              ],
            ],
          ),
          const SizedBox(height: 10),
          const ShanganLegend(
            items: [
              ShanganLegendItem(
                color: ShanganColors.green,
                label: '全部完成',
                bar: true,
              ),
              ShanganLegendItem(
                color: ShanganColors.blue,
                label: '部分完成',
                bar: true,
              ),
              ShanganLegendItem(
                color: ShanganColors.red,
                label: '零完成',
                bar: true,
              ),
              ShanganLegendItem(
                color: ShanganColors.inkSoft,
                label: '无待办',
                bar: true,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

final class _RecentDayTile extends StatelessWidget {
  const _RecentDayTile({
    required this.summary,
    required this.selected,
    required this.onTap,
  });

  final DaySummary summary;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final outcome = dayOutcomeStyle(summary.outcome);
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 7),
        decoration: BoxDecoration(
          color: selected ? ShanganColors.blueSoft : null,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: selected ? ShanganColors.blue : ShanganColors.hair,
            width: selected ? 1.5 : 1,
          ),
        ),
        child: Column(
          children: [
            Text(
              weekdayLabel(summary.date),
              style: TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: selected ? ShanganColors.blue : ShanganColors.mutedInk,
              ),
            ),
            Text(
              '${summary.date.day}',
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w800,
                color: selected ? ShanganColors.blue : ShanganColors.ink,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
            const SizedBox(height: 4),
            Container(
              width: 16,
              height: 3,
              decoration: BoxDecoration(
                color: outcome.color,
                borderRadius: BorderRadius.circular(9),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 原型 `.field`：1.5px 描边圆角 12 的输入框，内含 11px 小标签。
///
/// 聚焦时描边变 2px 蓝（原型 `.field.focus`）。对应 2-3、2-4、6-2 的表单字段。
final class ShanganField extends StatefulWidget {
  const ShanganField({
    required this.label,
    required this.controller,
    this.hint,
    this.keyboardType,
    this.minLines = 1,
    this.maxLines = 1,
    this.onChanged,
    this.readOnly = false,
    this.onTap,
    super.key,
  });

  final String label;
  final TextEditingController controller;
  final String? hint;
  final TextInputType? keyboardType;
  final int minLines;
  final int maxLines;
  final ValueChanged<String>? onChanged;
  final bool readOnly;
  final VoidCallback? onTap;

  @override
  State<ShanganField> createState() => _ShanganFieldState();
}

class _ShanganFieldState extends State<ShanganField> {
  final _focusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _focusNode.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _focusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final focused = _focusNode.hasFocus;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: ShanganColors.surface,
        borderRadius: BorderRadius.circular(ShanganRadius.field),
        border: Border.all(
          color: focused ? ShanganColors.blue : ShanganColors.rule,
          width: focused ? 2 : ShanganRadius.borderWidth,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            widget.label,
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.4,
              color: ShanganColors.mutedInk,
            ),
          ),
          const SizedBox(height: 5),
          TextField(
            controller: widget.controller,
            focusNode: _focusNode,
            keyboardType: widget.keyboardType,
            minLines: widget.minLines,
            maxLines: widget.maxLines,
            onChanged: widget.onChanged,
            readOnly: widget.readOnly,
            onTap: widget.onTap,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
            decoration: InputDecoration(
              isDense: true,
              filled: false,
              hintText: widget.hint,
              border: InputBorder.none,
              enabledBorder: InputBorder.none,
              focusedBorder: InputBorder.none,
              contentPadding: EdgeInsets.zero,
            ),
          ),
        ],
      ),
    );
  }
}

/// 原型 `.switch` 所在的开关行：左侧标题 + 说明，右侧 44x26 开关。
final class ShanganSwitchTile extends StatelessWidget {
  const ShanganSwitchTile({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
    super.key,
  });

  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                subtitle,
                style: const TextStyle(
                  fontSize: 11.5,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 10),
        Switch(
          value: value,
          onChanged: onChanged,
          thumbColor: const WidgetStatePropertyAll(Colors.white),
          trackColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected)
                ? ShanganColors.green
                : ShanganColors.rule,
          ),
          trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
        ),
      ],
    );
  }
}

/// 原型 2-4：一张卡里叠放多个开关，用 hair 分隔线隔开。
final class ShanganSwitchCard extends StatelessWidget {
  const ShanganSwitchCard({required this.tiles, super.key});

  final List<ShanganSwitchTile> tiles;

  @override
  Widget build(BuildContext context) {
    return ShanganCard(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var index = 0; index < tiles.length; index++) ...[
            if (index > 0)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Divider(height: 1, color: ShanganColors.hair),
              ),
            tiles[index],
          ],
        ],
      ),
    );
  }
}

/// 原型 `.search`：1.5px 描边圆角 14 的搜索框。
final class ShanganSearchField extends StatelessWidget {
  const ShanganSearchField({
    required this.controller,
    required this.hint,
    this.onChanged,
    super.key,
  });

  final TextEditingController controller;
  final String hint;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 4),
      decoration: BoxDecoration(
        color: ShanganColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: ShanganColors.rule,
          width: ShanganRadius.borderWidth,
        ),
      ),
      child: Row(
        children: [
          const Icon(Icons.search, size: 18, color: ShanganColors.mutedInk),
          const SizedBox(width: 9),
          Expanded(
            child: TextField(
              controller: controller,
              onChanged: onChanged,
              style: const TextStyle(fontSize: 14),
              decoration: InputDecoration(
                isDense: true,
                filled: false,
                hintText: hint,
                hintStyle: const TextStyle(
                  fontSize: 14,
                  color: ShanganColors.mutedInk,
                ),
                border: InputBorder.none,
                enabledBorder: InputBorder.none,
                focusedBorder: InputBorder.none,
                contentPadding: EdgeInsets.symmetric(vertical: 11),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 原型 `.sheet` 的统一包装：圆角 24、蓝色描边、最大高度 86%。
///
/// 三条添加流程与完成回填都在同一个 Bottom Sheet 栈内完成，不跳全屏页。
Future<bool> showShanganSheet(
  BuildContext context, {
  required Widget Function(BuildContext context) builder,
  double heightFactor = 0.86,
}) async {
  final result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    constraints: BoxConstraints(
      maxHeight: MediaQuery.of(context).size.height * heightFactor,
    ),
    builder: (sheetContext) => Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(sheetContext).viewInsets.bottom,
      ),
      child: builder(sheetContext),
    ),
  );
  return result ?? false;
}

/// 原型 `.sheet h4` + `.sub`：弹层标题与说明，右侧可放步骤提示。
final class ShanganSheetHeader extends StatelessWidget {
  const ShanganSheetHeader({
    required this.title,
    this.subtitle,
    this.step,
    super.key,
  });

  final String title;
  final String? subtitle;
  final String? step;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Text(
                title,
                style: const TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            if (step != null)
              Text(
                step!,
                style: const TextStyle(
                  fontSize: 12.5,
                  color: ShanganColors.mutedInk,
                ),
              ),
          ],
        ),
        if (subtitle != null) ...[
          const SizedBox(height: 4),
          Text(
            subtitle!,
            style: const TextStyle(
              fontSize: 12.5,
              height: 1.6,
              color: ShanganColors.mutedInk,
            ),
          ),
        ],
      ],
    );
  }
}

/// 原型 `.dotstate`：色点 + 文字的在线状态，不只依赖颜色表达。
final class ShanganDotState extends StatelessWidget {
  const ShanganDotState({required this.state, required this.label, super.key});

  final PresenceState state;
  final String label;

  @override
  Widget build(BuildContext context) {
    final color = switch (state) {
      PresenceState.online => ShanganColors.green,
      PresenceState.idle => ShanganColors.ochre,
      PresenceState.offline => ShanganColors.red,
    };
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Text(
          label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w700,
            color: color,
          ),
        ),
      ],
    );
  }
}
