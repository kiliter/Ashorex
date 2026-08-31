import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// 页面统一的水平留白，确保 393pt 画布上与高保真原型一致。
const shanganPagePadding = EdgeInsets.fromLTRB(20, 20, 20, 112);

/// 原型中的等宽数字样式，用于时长、日期与统计数字。
TextStyle shanganNumberStyle(BuildContext context, {double? fontSize}) {
  return Theme.of(context).textTheme.titleLarge!.copyWith(
    fontFamily: 'SF Mono',
    fontFamilyFallback: const ['Menlo'],
    fontSize: fontSize,
    fontFeatures: const [FontFeature.tabularFigures()],
    letterSpacing: -0.5,
  );
}

/// 小型结构标签，不依赖颜色表达信息。
final class ShanganEyebrow extends StatelessWidget {
  const ShanganEyebrow(this.text, {super.key, this.color});

  final String text;
  final Color? color;

  @override
  Widget build(BuildContext context) => Text(
    text,
    style: Theme.of(context).textTheme.labelSmall?.copyWith(
      color: color ?? ShanganColors.mutedInk,
      fontFamily: 'SF Mono',
      fontFamilyFallback: const ['Menlo'],
      fontWeight: FontWeight.w700,
      letterSpacing: 1.1,
      height: 1.4,
    ),
  );
}

enum ShanganTagTone { neutral, info, success, warning, risk }

/// 文字、形状与颜色共同表达状态的标签。
final class ShanganStatusTag extends StatelessWidget {
  const ShanganStatusTag(
    this.label, {
    super.key,
    this.tone = ShanganTagTone.neutral,
  });

  final String label;
  final ShanganTagTone tone;

  @override
  Widget build(BuildContext context) {
    final (color, background, shape) = switch (tone) {
      ShanganTagTone.info => (
        ShanganColors.blue,
        ShanganColors.blueSoft,
        BoxShape.circle,
      ),
      ShanganTagTone.success => (
        ShanganColors.green,
        ShanganColors.greenSoft,
        BoxShape.rectangle,
      ),
      ShanganTagTone.warning => (
        ShanganColors.ochre,
        ShanganColors.ochreSoft,
        BoxShape.rectangle,
      ),
      ShanganTagTone.risk => (
        ShanganColors.red,
        ShanganColors.redSoft,
        BoxShape.circle,
      ),
      ShanganTagTone.neutral => (
        ShanganColors.mutedInk,
        ShanganColors.inkSoft,
        BoxShape.circle,
      ),
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        color: background,
        border: Border.all(color: color.withValues(alpha: 0.45)),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 7,
              height: 7,
              decoration: BoxDecoration(
                color: shape == BoxShape.rectangle ? color : Colors.transparent,
                shape: shape,
                border: Border.all(color: color),
                borderRadius: shape == BoxShape.rectangle
                    ? BorderRadius.circular(1)
                    : null,
              ),
            ),
            const SizedBox(width: 6),
            Text(
              label,
              style: Theme.of(context).textTheme.labelSmall
                  ?.copyWith(color: color, fontWeight: FontWeight.w700),
            ),
          ],
        ),
      ),
    );
  }
}

/// 纸张表面容器；边框宽度和圆角直接来自原型规范。
final class ShanganSurface extends StatelessWidget {
  const ShanganSurface({
    required this.child,
    super.key,
    this.padding = const EdgeInsets.all(17),
    this.borderColor = ShanganColors.rule,
    this.backgroundColor = ShanganColors.surface,
    this.dashed = false,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final Color borderColor;
  final Color backgroundColor;
  final bool dashed;

  @override
  Widget build(BuildContext context) {
    final content = DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        border: dashed ? null : Border.all(color: borderColor, width: 1.5),
        borderRadius: BorderRadius.circular(18),
        boxShadow: [
          BoxShadow(
            color: borderColor.withValues(alpha: 0.16),
            offset: const Offset(4, 4),
          ),
        ],
      ),
      child: Padding(padding: padding, child: child),
    );
    if (!dashed) return content;
    return CustomPaint(
      painter: _DashedRoundedBorderPainter(color: borderColor),
      child: content,
    );
  }
}

/// 左侧墨线通知，适合业务解释和规则提示。
final class ShanganNotice extends StatelessWidget {
  const ShanganNotice({
    required this.title,
    required this.message,
    super.key,
    this.tone = ShanganTagTone.info,
  });

  final String title;
  final String message;
  final ShanganTagTone tone;

  @override
  Widget build(BuildContext context) {
    final color = switch (tone) {
      ShanganTagTone.risk => ShanganColors.red,
      ShanganTagTone.success => ShanganColors.green,
      ShanganTagTone.warning => ShanganColors.ochre,
      _ => ShanganColors.blue,
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border(
          left: BorderSide(color: color, width: 3),
          bottom: const BorderSide(color: ShanganColors.rule),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 9, 4, 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(message, style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
    );
  }
}

/// 四格统计表的单元数据。
typedef ShanganMetric = ({String value, String label, ShanganTagTone tone});

/// 无阴影卡片堆叠的统计网格，网格线本身承担信息结构。
final class ShanganMetricGrid extends StatelessWidget {
  const ShanganMetricGrid({required this.metrics, super.key});

  final List<ShanganMetric> metrics;

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(17),
    child: DecoratedBox(
      decoration: BoxDecoration(
        color: ShanganColors.surface,
        border: Border.all(color: ShanganColors.blue, width: 1.5),
        borderRadius: BorderRadius.circular(17),
      ),
      child: GridView.builder(
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          mainAxisExtent: 70,
        ),
        itemCount: metrics.length,
        itemBuilder: (context, index) {
          final metric = metrics[index];
          final color = switch (metric.tone) {
            ShanganTagTone.success => ShanganColors.green,
            ShanganTagTone.warning => ShanganColors.ochre,
            ShanganTagTone.risk => ShanganColors.red,
            _ => ShanganColors.blue,
          };
          return DecoratedBox(
            decoration: BoxDecoration(
              border: Border(
                left: index.isOdd
                    ? const BorderSide(color: ShanganColors.rule)
                    : BorderSide.none,
                top: index >= 2
                    ? const BorderSide(color: ShanganColors.rule)
                    : BorderSide.none,
              ),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    metric.value,
                    style: shanganNumberStyle(
                      context,
                      fontSize: 20,
                    ).copyWith(color: color),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    metric.label,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          );
        },
      ),
    ),
  );
}

/// 同时显示服务端可信边界、当前播放位置与完成阈值的刻度轨。
final class ShanganTrustScale extends StatelessWidget {
  const ShanganTrustScale({
    required this.label,
    required this.valueLabel,
    required this.trustedFraction,
    required this.positionFraction,
    required this.thresholdFraction,
    super.key,
  });

  final String label;
  final String valueLabel;
  final double trustedFraction;
  final double positionFraction;
  final double thresholdFraction;

  @override
  Widget build(BuildContext context) => Semantics(
    label: '$label，$valueLabel',
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: Text(label, style: Theme.of(context).textTheme.labelLarge),
            ),
            Text(valueLabel, style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
        const SizedBox(height: 8),
        SizedBox(
          height: 24,
          child: CustomPaint(
            painter: _TrustScalePainter(
              trustedFraction: trustedFraction.clamp(0, 1),
              positionFraction: positionFraction.clamp(0, 1),
              thresholdFraction: thresholdFraction.clamp(0, 1),
            ),
          ),
        ),
        const Wrap(
          spacing: 13,
          runSpacing: 4,
          children: [
            _Legend(label: '已验证', color: ShanganColors.blue),
            _Legend(label: '已播放', color: ShanganColors.mutedInk),
            _Legend(label: '尚不可跳转', color: ShanganColors.rule, dashed: true),
          ],
        ),
      ],
    ),
  );
}

final class _Legend extends StatelessWidget {
  const _Legend({
    required this.label,
    required this.color,
    this.dashed = false,
  });

  final String label;
  final Color color;
  final bool dashed;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      SizedBox(
        width: 13,
        child: Row(
          children: List.generate(
            dashed ? 3 : 1,
            (_) => Expanded(
              child: Container(
                height: 3,
                margin: EdgeInsets.only(right: dashed ? 1 : 0),
                color: color,
              ),
            ),
          ),
        ),
      ),
      const SizedBox(width: 4),
      Text(
        label,
        style: Theme.of(context).textTheme.labelSmall
            ?.copyWith(color: ShanganColors.mutedInk),
      ),
    ],
  );
}

/// 带荧光笔斜纹的业务进度条。
final class ShanganProgress extends StatelessWidget {
  const ShanganProgress({
    required this.value,
    super.key,
    this.color = ShanganColors.blue,
  });

  final double value;
  final Color color;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 9,
    child: CustomPaint(
      painter: _StripedProgressPainter(value: value.clamp(0, 1), color: color),
    ),
  );
}

/// 统一的加载状态，文案明确说明正在发生的操作。
final class ShanganLoading extends StatelessWidget {
  const ShanganLoading(this.label, {super.key});

  final String label;

  @override
  Widget build(BuildContext context) => Center(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const SizedBox.square(
          dimension: 28,
          child: CircularProgressIndicator(strokeWidth: 2.5),
        ),
        const SizedBox(height: 12),
        Text(label, style: Theme.of(context).textTheme.bodySmall),
      ],
    ),
  );
}

/// 把秒数格式化为适合学习时长展示的中文文本。
String shanganDuration(int seconds) {
  final safe = math.max(0, seconds);
  final hours = safe ~/ 3600;
  final minutes = (safe % 3600) ~/ 60;
  if (hours > 0) return '$hours 小时 ${minutes.toString().padLeft(2, '0')} 分';
  return '$minutes 分钟';
}

final class _TrustScalePainter extends CustomPainter {
  const _TrustScalePainter({
    required this.trustedFraction,
    required this.positionFraction,
    required this.thresholdFraction,
  });

  final double trustedFraction;
  final double positionFraction;
  final double thresholdFraction;

  @override
  void paint(Canvas canvas, Size size) {
    final rule = Paint()..color = ShanganColors.rule.withValues(alpha: 0.65);
    for (double x = 0; x <= size.width; x += 20) {
      canvas.drawRect(Rect.fromLTWH(x, 0, 1, 12), rule);
    }
    canvas.drawRect(
      Rect.fromLTWH(0, 0, size.width, 2),
      Paint()..color = ShanganColors.blue,
    );
    canvas.drawRect(
      Rect.fromLTWH(0, 0, size.width * trustedFraction, 6),
      Paint()..color = ShanganColors.blue,
    );
    canvas.drawRect(
      Rect.fromLTWH(0, 9, size.width * positionFraction, 3),
      Paint()..color = ShanganColors.mutedInk,
    );
    final blocked = Paint()..color = ShanganColors.rule;
    for (double x = size.width * positionFraction; x < size.width; x += 8) {
      canvas.drawRect(Rect.fromLTWH(x, 9, 4, 3), blocked);
    }
    canvas.drawRect(
      Rect.fromLTWH(size.width * positionFraction, 5, 2, 18),
      Paint()..color = ShanganColors.ink,
    );
    canvas.drawRect(
      Rect.fromLTWH(size.width * thresholdFraction, 0, 2, 15),
      Paint()..color = ShanganColors.red,
    );
  }

  @override
  bool shouldRepaint(_TrustScalePainter oldDelegate) =>
      oldDelegate.trustedFraction != trustedFraction ||
      oldDelegate.positionFraction != positionFraction ||
      oldDelegate.thresholdFraction != thresholdFraction;
}

final class _StripedProgressPainter extends CustomPainter {
  const _StripedProgressPainter({required this.value, required this.color});

  final double value;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final radius = Radius.circular(size.height / 2);
    canvas.drawRRect(
      RRect.fromRectAndRadius(Offset.zero & size, radius),
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5
        ..color = color,
    );
    final fillWidth = (size.width - 2) * value;
    if (fillWidth <= 0) return;
    canvas.save();
    canvas.clipRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(1, 1, fillWidth, size.height - 2),
        radius,
      ),
    );
    canvas.drawRect(
      Rect.fromLTWH(1, 1, fillWidth, size.height - 2),
      Paint()..color = color,
    );
    final stripe = Paint()..color = Colors.white.withValues(alpha: 0.22);
    for (double x = -size.height; x < fillWidth + size.height; x += 13) {
      canvas.drawLine(
        Offset(x, size.height),
        Offset(x + size.height, 0),
        stripe..strokeWidth = 4,
      );
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_StripedProgressPainter oldDelegate) =>
      oldDelegate.value != value || oldDelegate.color != color;
}

final class _DashedRoundedBorderPainter extends CustomPainter {
  const _DashedRoundedBorderPainter({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final path = Path()
      ..addRRect(
        RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(18)),
      );
    final metric = path.computeMetrics().first;
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    for (double distance = 0; distance < metric.length; distance += 9) {
      canvas.drawPath(
        metric.extractPath(distance, math.min(distance + 5, metric.length)),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(_DashedRoundedBorderPainter oldDelegate) =>
      oldDelegate.color != color;
}
