import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// Pad 工作台共用几何与表面，取值对齐 ashorex-pad-v2.html 的 `.pad-mode`。
abstract final class PadChrome {
  static const railWidth = 84.0;
  static const pagePadding = 28.0;
  static const asideWidth = 278.0;
  static const columnGap = 22.0;
  static const drawerWidth = 400.0;
  static const cardRadius = 18.0;
  static const metricMinHeight = 99.0;

  static const hairBorder = BorderSide(color: ShanganColors.hair);

  static const cardShadow = [
    BoxShadow(color: Color(0x08263B60), blurRadius: 12, offset: Offset(0, 3)),
  ];
}

/// 把毫秒格式化为 Pad 指标用的 `1h20m` / `50m`。
String formatPadMetricDuration(int milliseconds) {
  final totalMinutes = (milliseconds / 60000).floor();
  if (totalMinutes < 60) return '${totalMinutes}m';
  final hours = totalMinutes ~/ 60;
  final minutes = totalMinutes % 60;
  if (minutes == 0) return '${hours}h';
  return '${hours}h${minutes.toString().padLeft(2, '0')}m';
}

/// Pad 卡片：1px 浅描边 + 极轻投影，替代手机页 1.5px 深描边。
final class PadSurface extends StatelessWidget {
  const PadSurface({
    required this.child,
    this.padding,
    this.radius = PadChrome.cardRadius,
    this.backgroundColor = ShanganColors.surface,
    this.borderColor = ShanganColors.hair,
    this.gradient,
    this.minHeight,
    super.key,
  });

  final Widget child;
  final EdgeInsetsGeometry? padding;
  final double radius;
  final Color backgroundColor;
  final Color borderColor;
  final Gradient? gradient;
  final double? minHeight;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: minHeight == null
          ? null
          : BoxConstraints(minHeight: minHeight!),
      padding: padding,
      decoration: BoxDecoration(
        color: gradient == null ? backgroundColor : null,
        gradient: gradient,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: borderColor),
        boxShadow: PadChrome.cardShadow,
      ),
      child: child,
    );
  }
}

/// Pad 页头：英文眉题 + 大标题 + 可选副文案。
final class PadPageHeader extends StatelessWidget {
  const PadPageHeader({
    required this.eyebrow,
    required this.title,
    this.subtitle,
    this.actions = const [],
    super.key,
  });

  final String eyebrow;
  final String title;
  final String? subtitle;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                eyebrow,
                style: const TextStyle(
                  fontSize: 9,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1.8,
                  color: ShanganColors.mutedInk,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 27,
                  height: 1.2,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.9,
                ),
              ),
              if (subtitle != null) ...[
                const SizedBox(height: 5),
                Text(
                  subtitle!,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 11,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ],
          ),
        ),
        if (actions.isNotEmpty)
          Row(mainAxisSize: MainAxisSize.min, children: actions),
      ],
    );
  }
}

/// Pad 四格指标卡：标签在上、数字居中、右上角语义图标。
final class PadMetricCard extends StatelessWidget {
  const PadMetricCard({
    required this.label,
    required this.value,
    required this.note,
    required this.icon,
    this.emphasized = false,
    this.accent = ShanganColors.blue,
    this.accentSoft = ShanganColors.blueSoft,
    super.key,
  });

  final String label;
  final String value;
  final String note;
  final IconData icon;
  final bool emphasized;
  final Color accent;
  final Color accentSoft;

  @override
  Widget build(BuildContext context) {
    return PadSurface(
      minHeight: PadChrome.metricMinHeight,
      padding: const EdgeInsets.fromLTRB(18, 15, 18, 13),
      child: Stack(
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: const TextStyle(
                  fontSize: 10,
                  color: ShanganColors.mutedInk,
                ),
              ),
              const SizedBox(height: 7),
              Text(
                value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 28,
                  height: 1.15,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -1,
                  color: emphasized ? accent : ShanganColors.ink,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
              const SizedBox(height: 8),
              Text(
                note,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 9,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
          ),
          Positioned(
            right: 0,
            top: 0,
            child: Container(
              width: 30,
              height: 30,
              decoration: BoxDecoration(
                color: emphasized ? accentSoft : ShanganColors.inkSoft,
                borderRadius: BorderRadius.circular(9),
              ),
              child: Icon(
                icon,
                size: 15,
                color: emphasized ? accent : ShanganColors.mutedInk,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 圆环进度：外圈 conic，内圆挖空后放数字。
final class PadProgressDial extends StatelessWidget {
  const PadProgressDial({
    required this.progress,
    required this.child,
    this.size = 64,
    this.color = ShanganColors.blue,
    this.trackColor = ShanganColors.blueSoft,
    this.strokeWidth = 7,
    super.key,
  });

  final double progress;
  final Widget child;
  final double size;
  final Color color;
  final Color trackColor;
  final double strokeWidth;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(
        painter: _PadDialPainter(
          progress: progress.clamp(0, 1),
          color: color,
          trackColor: trackColor,
          strokeWidth: strokeWidth,
        ),
        child: Center(child: child),
      ),
    );
  }
}

final class _PadDialPainter extends CustomPainter {
  const _PadDialPainter({
    required this.progress,
    required this.color,
    required this.trackColor,
    required this.strokeWidth,
  });

  final double progress;
  final Color color;
  final Color trackColor;
  final double strokeWidth;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.shortestSide / 2 - strokeWidth / 2;
    final rect = Rect.fromCircle(center: center, radius: radius);
    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..color = trackColor
      ..strokeCap = StrokeCap.butt;
    canvas.drawCircle(center, radius, track);
    if (progress <= 0) return;
    final sweep = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..color = color
      ..strokeCap = StrokeCap.butt;
    canvas.drawArc(rect, -math.pi / 2, 2 * math.pi * progress, false, sweep);
  }

  @override
  bool shouldRepaint(covariant _PadDialPainter oldDelegate) =>
      oldDelegate.progress != progress ||
      oldDelegate.color != color ||
      oldDelegate.trackColor != trackColor ||
      oldDelegate.strokeWidth != strokeWidth;
}
