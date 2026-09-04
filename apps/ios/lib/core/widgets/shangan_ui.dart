import 'dart:async';
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
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: color,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 所有课时入口共用的观看进度样式，统一状态文字、时长和进度条。
final class ShanganWatchProgress extends StatelessWidget {
  const ShanganWatchProgress({
    required this.progressPercent,
    required this.completed,
    super.key,
    this.durationSeconds,
    this.meta,
  });

  final int progressPercent;
  final bool completed;
  final int? durationSeconds;
  final String? meta;

  @override
  Widget build(BuildContext context) {
    final percent = (completed ? 100 : progressPercent).clamp(0, 100);
    final label = completed
        ? '已看完'
        : percent > 0
        ? '已观看 $percent%'
        : '未观看';
    final tone = completed
        ? ShanganTagTone.success
        : percent > 0
        ? ShanganTagTone.info
        : ShanganTagTone.neutral;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 5,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            if (meta != null && meta!.isNotEmpty)
              Text(meta!, style: Theme.of(context).textTheme.bodySmall),
            ShanganStatusTag(label, tone: tone),
            if (durationSeconds != null)
              Text(
                shanganDuration(durationSeconds!),
                style: Theme.of(context).textTheme.bodySmall,
              ),
          ],
        ),
        const SizedBox(height: 8),
        ShanganProgress(
          value: percent / 100,
          style: ShanganProgressStyle.track,
        ),
      ],
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
    const radius = BorderRadius.all(Radius.circular(18));
    // 后层空心灰卡向右下错开的距离，对齐参考图那种叠放纸片。
    const shift = Offset(8, 7);
    // 前层必须拉满可用宽度，否则在 stretch 布局里会缩成内容宽，后层灰卡铺满剩下的空白。
    final front = SizedBox(
      width: double.infinity,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: backgroundColor,
          border: dashed ? null : Border.all(color: borderColor, width: 1.5),
          borderRadius: radius,
          boxShadow: dashed
              ? null
              : const [
                  BoxShadow(
                    color: Color(0x1A263B60),
                    offset: Offset(0, 1),
                    blurRadius: 3,
                  ),
                ],
        ),
        child: Padding(padding: padding, child: child),
      ),
    );
    if (dashed) {
      return CustomPaint(
        painter: _DashedRoundedBorderPainter(color: borderColor),
        child: front,
      );
    }
    // 前层主卡覆盖后层同尺寸灰卡，只露出右下边，中间透出纸底形成空隙。
    return Padding(
      padding: EdgeInsets.only(right: shift.dx, bottom: shift.dy),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: IgnorePointer(
              child: Transform.translate(
                offset: shift,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: const Color(0xFFE6ECF3),
                    borderRadius: radius,
                    border: Border.all(
                      color: const Color(0xFFB4C0D0),
                      width: 1.5,
                    ),
                  ),
                ),
              ),
            ),
          ),
          front,
        ],
      ),
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
    this.boxed = false,
  });

  final String title;
  final String message;
  final ShanganTagTone tone;

  /// 日报/周报等卡片内用完整描边，避免只剩左线和底线显得残缺。
  final bool boxed;

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
        color: boxed ? color.withValues(alpha: 0.06) : null,
        border: boxed
            ? Border.all(color: color.withValues(alpha: 0.55), width: 1.5)
            : Border(
                left: BorderSide(color: color, width: 3),
                bottom: const BorderSide(color: ShanganColors.rule),
              ),
        borderRadius: boxed ? BorderRadius.circular(12) : null,
      ),
      child: Padding(
        padding: EdgeInsets.fromLTRB(boxed ? 12 : 12, 9, boxed ? 12 : 4, 10),
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

/// 设置类入口行，用于「我的」和列表页，保持 44pt 点击区。
final class ShanganNavRow extends StatelessWidget {
  const ShanganNavRow({
    required this.title,
    required this.onTap,
    super.key,
    this.icon,
    this.subtitle,
    this.trailing,
  });

  final String title;
  final VoidCallback onTap;
  final IconData? icon;
  final String? subtitle;
  final String? trailing;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 52),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 8),
            child: Row(
              children: [
                if (icon != null) ...[
                  Container(
                    width: 40,
                    height: 40,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: ShanganColors.blueSoft,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(icon, color: ShanganColors.blue, size: 20),
                  ),
                  const SizedBox(width: 12),
                ],
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      if (subtitle != null) ...[
                        const SizedBox(height: 2),
                        Text(
                          subtitle!,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ],
                  ),
                ),
                if (trailing != null)
                  Text(trailing!, style: Theme.of(context).textTheme.bodySmall),
                const Icon(Icons.chevron_right, color: ShanganColors.mutedInk),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 四格统计表的单元数据。
typedef ShanganMetric = ({String value, String label, ShanganTagTone tone});

/// 无阴影卡片堆叠的统计网格，网格线本身承担信息结构。
final class ShanganMetricGrid extends StatelessWidget {
  const ShanganMetricGrid({
    required this.metrics,
    super.key,
    this.embedded = false,
  });

  final List<ShanganMetric> metrics;

  /// 嵌在 [ShanganSurface] 内时去掉外框，避免双边框。
  final bool embedded;

  @override
  Widget build(BuildContext context) {
    final grid = GridView.builder(
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
                FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Text(
                    metric.value,
                    maxLines: 1,
                    style: shanganNumberStyle(
                      context,
                      fontSize: 20,
                    ).copyWith(color: color),
                  ),
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
    );
    if (embedded) return grid;
    return ClipRRect(
      borderRadius: BorderRadius.circular(17),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: ShanganColors.surface,
          border: Border.all(color: ShanganColors.blue, width: 1.5),
          borderRadius: BorderRadius.circular(17),
        ),
        child: grid,
      ),
    );
  }
}

/// 入场缓动与静止轻脉冲。
///
/// 脉冲由 [Timer] 触发一次有限 [AnimationController.forward]，
/// 不用 [AnimationController.repeat]，避免 widget 测试 `pumpAndSettle` 挂起。
final class ShanganIdleMotion {
  ShanganIdleMotion({
    required TickerProvider vsync,
    required VoidCallback onTick,
    this.fillDuration = const Duration(milliseconds: 800),
    this.pulseDuration = const Duration(milliseconds: 720),
    this.idlePeriod = const Duration(seconds: 4),
  }) : _onTick = onTick,
       fill = AnimationController(vsync: vsync, duration: fillDuration)
         ..addListener(onTick),
       pulse = AnimationController(vsync: vsync, duration: pulseDuration)
         ..addListener(onTick) {
    displayed = const AlwaysStoppedAnimation<double>(0);
  }

  final Duration fillDuration;
  final Duration pulseDuration;
  final Duration idlePeriod;
  final VoidCallback _onTick;
  final AnimationController fill;
  final AnimationController pulse;
  late Animation<double> displayed;
  Timer? _idle;

  /// 当前展示的 0–1 进度。
  double get value => displayed.value;

  /// 静止脉冲相位，0 表示完全静止。
  double get pulseValue => Curves.easeInOut.transform(pulse.value);

  /// 启动入场填充并开始静止计时。
  void start({required double target, required bool Function() reduceMotion}) {
    animateTo(target, fromZero: true, reduceMotion: reduceMotion());
    startIdle(reduceMotion);
  }

  /// 只启动静止脉冲，用于滚动条这类没有填充目标的控件。
  void startIdle(bool Function() reduceMotion) {
    _idle?.cancel();
    _idle = Timer.periodic(idlePeriod, (_) {
      if (reduceMotion()) return;
      pulse.forward(from: 0);
    });
  }

  /// 用户刚滚动或拖动后，打断当前脉冲并重新计时。
  void restartIdle(bool Function() reduceMotion) {
    if (pulse.isAnimating || pulse.value > 0) {
      pulse.stop();
      pulse.reset();
      _onTick();
    }
    startIdle(reduceMotion);
  }

  /// 把展示值缓动到 [next]；系统「减弱动态效果」时直接跳到终值。
  void animateTo(
    double next, {
    bool fromZero = false,
    required bool reduceMotion,
    Duration? duration,
  }) {
    final end = next.clamp(0.0, 1.0);
    if (reduceMotion) {
      displayed = AlwaysStoppedAnimation(end);
      _onTick();
      return;
    }
    fill.duration =
        duration ??
        (fromZero ? fillDuration : const Duration(milliseconds: 450));
    displayed = Tween<double>(
      begin: fromZero ? 0 : displayed.value,
      end: end,
    ).animate(CurvedAnimation(parent: fill, curve: Curves.easeOutCubic));
    fill.forward(from: 0);
  }

  void dispose() {
    _idle?.cancel();
    fill.dispose();
    pulse.dispose();
  }
}

/// 同时显示服务端可信边界、当前播放位置与完成阈值的刻度轨。
final class ShanganTrustScale extends StatefulWidget {
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
  State<ShanganTrustScale> createState() => _ShanganTrustScaleState();
}

final class _ShanganTrustScaleState extends State<ShanganTrustScale>
    with TickerProviderStateMixin {
  late final ShanganIdleMotion _motion;

  @override
  void initState() {
    super.initState();
    _motion = ShanganIdleMotion(
      vsync: this,
      onTick: () {
        if (mounted) setState(() {});
      },
      fillDuration: const Duration(milliseconds: 860),
      idlePeriod: const Duration(seconds: 5),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      // 入场只把刻度从 0 拉到当前值；之后播放位置更新直接跟新值，避免拖进度条时滞后。
      _motion.start(target: 1, reduceMotion: _reduceMotion);
    });
  }

  @override
  void dispose() {
    _motion.dispose();
    super.dispose();
  }

  bool _reduceMotion() => !mounted || MediaQuery.disableAnimationsOf(context);

  @override
  Widget build(BuildContext context) {
    final appear = _motion.value;
    return Semantics(
      label: '${widget.label}，${widget.valueLabel}',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Text(
                  widget.label,
                  style: Theme.of(context).textTheme.labelLarge,
                ),
              ),
              Text(
                widget.valueLabel,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(
            height: 24,
            child: CustomPaint(
              painter: _TrustScalePainter(
                trustedFraction: widget.trustedFraction.clamp(0, 1) * appear,
                positionFraction: widget.positionFraction.clamp(0, 1) * appear,
                thresholdFraction: widget.thresholdFraction.clamp(0, 1),
                pulse: _motion.pulseValue,
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
        style: Theme.of(
          context,
        ).textTheme.labelSmall?.copyWith(color: ShanganColors.mutedInk),
      ),
    ],
  );
}

enum ShanganProgressStyle { highlighter, track }

/// 带荧光笔斜纹的业务进度条；作战单等卡片内用细轨道，避免斜纹太抢。
final class ShanganProgress extends StatefulWidget {
  const ShanganProgress({
    required this.value,
    super.key,
    this.color = ShanganColors.blue,
    this.style = ShanganProgressStyle.highlighter,
  });

  final double value;
  final Color color;
  final ShanganProgressStyle style;

  @override
  State<ShanganProgress> createState() => _ShanganProgressState();
}

final class _ShanganProgressState extends State<ShanganProgress>
    with TickerProviderStateMixin {
  late final ShanganIdleMotion _motion;

  @override
  void initState() {
    super.initState();
    _motion = ShanganIdleMotion(
      vsync: this,
      onTick: () {
        if (mounted) setState(() {});
      },
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _motion.start(
        target: widget.value.clamp(0, 1),
        reduceMotion: _reduceMotion,
      );
    });
  }

  @override
  void didUpdateWidget(ShanganProgress oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.value != widget.value) {
      _motion.animateTo(
        widget.value.clamp(0, 1),
        reduceMotion: _reduceMotion(),
      );
    }
  }

  @override
  void dispose() {
    _motion.dispose();
    super.dispose();
  }

  bool _reduceMotion() => !mounted || MediaQuery.disableAnimationsOf(context);

  @override
  Widget build(BuildContext context) {
    final pulse = _motion.pulseValue;
    if (widget.style == ShanganProgressStyle.track) {
      return SizedBox(
        height: 8,
        width: double.infinity,
        child: CustomPaint(
          painter: _TrackProgressPainter(
            value: _motion.value,
            color: widget.color,
            pulse: pulse,
          ),
        ),
      );
    }
    return SizedBox(
      height: 9,
      width: double.infinity,
      child: CustomPaint(
        painter: _StripedProgressPainter(
          value: _motion.value,
          color: widget.color,
          pulse: pulse,
        ),
      ),
    );
  }
}

/// 百分比数字入场跳数，静止后偶尔轻轻胀一下。
final class ShanganCountUpPercent extends StatefulWidget {
  const ShanganCountUpPercent({
    required this.value,
    super.key,
    this.fontSize = 22,
    this.color,
  });

  final double value;
  final double fontSize;
  final Color? color;

  @override
  State<ShanganCountUpPercent> createState() => _ShanganCountUpPercentState();
}

final class _ShanganCountUpPercentState extends State<ShanganCountUpPercent>
    with TickerProviderStateMixin {
  late final ShanganIdleMotion _motion;

  @override
  void initState() {
    super.initState();
    _motion = ShanganIdleMotion(
      vsync: this,
      onTick: () {
        if (mounted) setState(() {});
      },
      fillDuration: const Duration(milliseconds: 800),
      pulseDuration: const Duration(milliseconds: 640),
      idlePeriod: const Duration(seconds: 4),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _motion.start(
        target: widget.value.clamp(0, 1),
        reduceMotion: _reduceMotion,
      );
    });
  }

  @override
  void didUpdateWidget(ShanganCountUpPercent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.value != widget.value) {
      _motion.animateTo(
        widget.value.clamp(0, 1),
        reduceMotion: _reduceMotion(),
      );
    }
  }

  @override
  void dispose() {
    _motion.dispose();
    super.dispose();
  }

  bool _reduceMotion() => !mounted || MediaQuery.disableAnimationsOf(context);

  @override
  Widget build(BuildContext context) {
    final pulse = _motion.pulseValue;
    final shown = (_motion.value * 100).round();
    return Transform.scale(
      scale: 1 + 0.04 * math.sin(math.pi * pulse),
      alignment: Alignment.centerRight,
      child: Text(
        '$shown%',
        style: shanganNumberStyle(
          context,
          fontSize: widget.fontSize,
        ).copyWith(color: widget.color ?? ShanganColors.blue),
      ),
    );
  }
}

/// 系统滚动条的静止呼吸：未滚动时每隔几秒加粗并提亮滑块。
final class ShanganIdleScrollbar extends StatefulWidget {
  const ShanganIdleScrollbar({
    required this.controller,
    required this.child,
    super.key,
    this.thumbVisibility = true,
  });

  final ScrollController controller;
  final Widget child;
  final bool thumbVisibility;

  @override
  State<ShanganIdleScrollbar> createState() => _ShanganIdleScrollbarState();
}

final class _ShanganIdleScrollbarState extends State<ShanganIdleScrollbar>
    with TickerProviderStateMixin {
  late final ShanganIdleMotion _motion;

  @override
  void initState() {
    super.initState();
    _motion = ShanganIdleMotion(
      vsync: this,
      onTick: () {
        if (mounted) setState(() {});
      },
      pulseDuration: const Duration(milliseconds: 700),
      idlePeriod: const Duration(seconds: 5),
    );
    widget.controller.addListener(_onScroll);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _motion.startIdle(_reduceMotion);
    });
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onScroll);
    _motion.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!mounted) return;
    _motion.restartIdle(_reduceMotion);
  }

  bool _reduceMotion() => !mounted || MediaQuery.disableAnimationsOf(context);

  @override
  Widget build(BuildContext context) {
    final breathe = math.sin(math.pi * _motion.pulseValue);
    return ScrollbarTheme(
      data: ScrollbarThemeData(
        thickness: WidgetStateProperty.all(5.5 + 3.2 * breathe),
        radius: const Radius.circular(8),
        thumbColor: WidgetStateProperty.all(
          Color.lerp(const Color(0xFF2C68B7), const Color(0xFF6AA0E6), breathe),
        ),
      ),
      child: Scrollbar(
        controller: widget.controller,
        thumbVisibility: widget.thumbVisibility,
        child: widget.child,
      ),
    );
  }
}

/// 日报/周报完成率：线框包裹圆环与大数字，避免大片留白。
final class ShanganCompletionHero extends StatefulWidget {
  const ShanganCompletionHero({
    required this.percent,
    required this.caption,
    super.key,
  });

  final int percent;
  final String caption;

  @override
  State<ShanganCompletionHero> createState() => _ShanganCompletionHeroState();
}

final class _ShanganCompletionHeroState extends State<ShanganCompletionHero>
    with TickerProviderStateMixin {
  late final ShanganIdleMotion _motion;

  @override
  void initState() {
    super.initState();
    _motion = ShanganIdleMotion(
      vsync: this,
      onTick: () {
        if (mounted) setState(() {});
      },
      fillDuration: const Duration(milliseconds: 900),
      pulseDuration: const Duration(milliseconds: 700),
      idlePeriod: const Duration(seconds: 5),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _motion.start(target: widget.percent / 100, reduceMotion: _reduceMotion);
    });
  }

  @override
  void didUpdateWidget(ShanganCompletionHero oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.percent != widget.percent) {
      _motion.animateTo(widget.percent / 100, reduceMotion: _reduceMotion());
    }
  }

  @override
  void dispose() {
    _motion.dispose();
    super.dispose();
  }

  bool _reduceMotion() => !mounted || MediaQuery.disableAnimationsOf(context);

  @override
  Widget build(BuildContext context) {
    final shown = (_motion.value * 100).round();
    final pulse = _motion.pulseValue;
    final scale = 1 + 0.045 * math.sin(math.pi * pulse);
    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border.all(color: ShanganColors.rule, width: 1.5),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 16, 12),
        child: Row(
          children: [
            Transform.scale(
              scale: scale,
              child: SizedBox(
                width: 64,
                height: 64,
                child: CustomPaint(
                  painter: _CompletionRingPainter(
                    value: _motion.value,
                    pulse: pulse,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Transform.scale(
                    scale: 1 + 0.03 * math.sin(math.pi * pulse),
                    alignment: Alignment.bottomLeft,
                    child: Text(
                      '$shown',
                      style: shanganNumberStyle(context, fontSize: 44),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(bottom: 6, left: 4),
                    child: Text(
                      '%',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        color: ShanganColors.blue,
                      ),
                    ),
                  ),
                  const Spacer(),
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Text(
                      widget.caption,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

final class _CompletionRingPainter extends CustomPainter {
  const _CompletionRingPainter({required this.value, this.pulse = 0});

  final double value;
  final double pulse;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.shortestSide / 2 - 4;
    final breathe = math.sin(math.pi * pulse);
    final track = Paint()
      ..color = const Color(0xFFEAF1FB)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 6
      ..strokeCap = StrokeCap.round;
    canvas.drawCircle(center, radius, track);
    if (value <= 0) {
      // 0% 时轨道本身轻轻呼吸，避免圆环完全死掉。
      if (pulse > 0) {
        canvas.drawCircle(
          center,
          radius,
          Paint()
            ..color = ShanganColors.blue.withValues(alpha: 0.18 * breathe)
            ..style = PaintingStyle.stroke
            ..strokeWidth = 6 + breathe
            ..strokeCap = StrokeCap.round,
        );
      }
      return;
    }
    final arc = Paint()
      ..color = Color.lerp(ShanganColors.blue, Colors.white, 0.12 * breathe)!
      ..style = PaintingStyle.stroke
      ..strokeWidth = 6 + 1.3 * breathe
      ..strokeCap = StrokeCap.round;
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius),
      -math.pi / 2,
      math.pi * 2 * value,
      false,
      arc,
    );
    if (pulse > 0) {
      final head = -math.pi / 2 + math.pi * 2 * value;
      canvas.drawCircle(
        Offset(
          center.dx + radius * math.cos(head),
          center.dy + radius * math.sin(head),
        ),
        3.2 + 1.4 * breathe,
        Paint()..color = Colors.white.withValues(alpha: 0.55 + 0.25 * breathe),
      );
    }
  }

  @override
  bool shouldRepaint(_CompletionRingPainter oldDelegate) =>
      oldDelegate.value != value || oldDelegate.pulse != pulse;
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

/// 课程与学习时长统一为 n.nh，例如 67 分钟显示为 1.1h。
String shanganDuration(int seconds) {
  final hours = math.max(0, seconds) / 3600;
  return '${hours.toStringAsFixed(1)}h';
}

/// 带正负号的时长，用于周报对比。
String shanganSignedDuration(int seconds) {
  final sign = seconds < 0 ? '-' : (seconds > 0 ? '+' : '');
  return '$sign${shanganDuration(seconds.abs())}';
}

/// 剩余时长用 mm:ss，避免和计划分钟文案混淆。
String shanganClock(int seconds) {
  final safe = math.max(0, seconds);
  final minutes = safe ~/ 60;
  final rest = safe % 60;
  return '${minutes.toString().padLeft(2, '0')}:${rest.toString().padLeft(2, '0')}';
}

/// 日期只比较年月日，忽略时钟和时区偏移。
bool shanganSameDay(DateTime left, DateTime right) {
  return left.year == right.year &&
      left.month == right.month &&
      left.day == right.day;
}

/// 取设备本地的当天日期，去掉时分秒，供作战单和日报判断“今天”。
DateTime shanganDeviceToday([DateTime? now]) {
  final value = now ?? DateTime.now();
  return DateTime(value.year, value.month, value.day);
}

/// 作战单路径使用的本地日期。
String shanganDateKey(DateTime date) {
  return '${date.year.toString().padLeft(4, '0')}-'
      '${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')}';
}

/// 把服务端 `date` 字段解析成本地年月日，避免 UTC 午夜被折到前一天。
DateTime shanganParseDate(String value) {
  final match = RegExp(r'^(\d{4})-(\d{2})-(\d{2})').firstMatch(value);
  if (match != null) {
    return DateTime(
      int.parse(match.group(1)!),
      int.parse(match.group(2)!),
      int.parse(match.group(3)!),
    );
  }
  final parsed = DateTime.parse(value);
  return DateTime(parsed.year, parsed.month, parsed.day);
}

final class _TrustScalePainter extends CustomPainter {
  const _TrustScalePainter({
    required this.trustedFraction,
    required this.positionFraction,
    required this.thresholdFraction,
    this.pulse = 0,
  });

  final double trustedFraction;
  final double positionFraction;
  final double thresholdFraction;
  final double pulse;

  @override
  void paint(Canvas canvas, Size size) {
    final breathe = math.sin(math.pi * pulse);
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
      Paint()
        ..color = Color.lerp(ShanganColors.blue, Colors.white, 0.18 * breathe)!,
    );
    canvas.drawRect(
      Rect.fromLTWH(0, 9, size.width * positionFraction, 3),
      Paint()..color = ShanganColors.mutedInk,
    );
    final blocked = Paint()..color = ShanganColors.rule;
    for (double x = size.width * positionFraction; x < size.width; x += 8) {
      canvas.drawRect(Rect.fromLTWH(x, 9, 4, 3), blocked);
    }
    // 静止时播放头轻轻左右点头，提示这条轨还能回看。
    final playheadX = (size.width * positionFraction + 2.4 * breathe).clamp(
      0.0,
      size.width - 2,
    );
    canvas.drawRect(
      Rect.fromLTWH(playheadX, 5, 2, 18),
      Paint()..color = ShanganColors.ink,
    );
    if (pulse > 0) {
      canvas.drawCircle(
        Offset(playheadX + 1, 8),
        2.4 + breathe,
        Paint()..color = ShanganColors.blue.withValues(alpha: 0.28 * breathe),
      );
    }
    canvas.drawRect(
      Rect.fromLTWH(size.width * thresholdFraction, 0, 2, 15),
      Paint()..color = ShanganColors.red,
    );
  }

  @override
  bool shouldRepaint(_TrustScalePainter oldDelegate) =>
      oldDelegate.trustedFraction != trustedFraction ||
      oldDelegate.positionFraction != positionFraction ||
      oldDelegate.thresholdFraction != thresholdFraction ||
      oldDelegate.pulse != pulse;
}

/// 作战单进度：浅底圆角槽 + 渐变填充，空进度不铺灰块。
final class _TrackProgressPainter extends CustomPainter {
  const _TrackProgressPainter({
    required this.value,
    required this.color,
    this.pulse = 0,
  });

  final double value;
  final Color color;
  final double pulse;

  @override
  void paint(Canvas canvas, Size size) {
    final breathe = math.sin(math.pi * pulse);
    final radius = Radius.circular(size.height / 2);
    final track = RRect.fromRectAndRadius(Offset.zero & size, radius);
    canvas.drawRRect(track, Paint()..color = const Color(0xFFEAF1FB));
    canvas.drawRRect(
      track,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1 + 0.5 * breathe
        ..color = Color.lerp(const Color(0xFFC5D4EA), color, 0.32 * breathe)!,
    );
    if (value <= 0) {
      // 空进度：左端一颗呼吸光点，提示这条轨还活着。
      if (pulse > 0) {
        final seedH = size.height - 3;
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromLTWH(1.5, 1.5, seedH + 4 * breathe, seedH),
            Radius.circular(seedH / 2),
          ),
          Paint()..color = color.withValues(alpha: 0.16 + 0.28 * breathe),
        );
      }
      return;
    }
    final inset = 1.5;
    final maxWidth = size.width - inset * 2;
    final fillWidth = (maxWidth * value).clamp(
      size.height - inset * 2,
      maxWidth,
    );
    final fillRect = Rect.fromLTWH(
      inset,
      inset,
      fillWidth,
      size.height - inset * 2,
    );
    final fill = RRect.fromRectAndRadius(
      fillRect,
      Radius.circular(fillRect.height / 2),
    );
    final bright = Color.lerp(color, Colors.white, 0.16 * breathe)!;
    canvas.drawRRect(
      fill,
      Paint()
        ..shader = LinearGradient(
          colors: [bright.withValues(alpha: 0.82), bright],
        ).createShader(fillRect),
    );
    canvas.drawRRect(
      RRect.fromLTRBR(
        fillRect.left + 2,
        fillRect.top + 1,
        fillRect.right - 2,
        fillRect.top + fillRect.height * 0.38,
        Radius.circular(fillRect.height / 2),
      ),
      Paint()..color = Colors.white.withValues(alpha: 0.28 + 0.18 * breathe),
    );
    if (pulse > 0) {
      final x = fillRect.left + fillRect.width * pulse;
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromCenter(
            center: Offset(x, fillRect.center.dy),
            width: 16,
            height: fillRect.height,
          ),
          Radius.circular(fillRect.height / 2),
        ),
        Paint()..color = Colors.white.withValues(alpha: 0.22 + 0.18 * breathe),
      );
    }
  }

  @override
  bool shouldRepaint(_TrackProgressPainter oldDelegate) =>
      oldDelegate.value != value ||
      oldDelegate.color != color ||
      oldDelegate.pulse != pulse;
}

final class _StripedProgressPainter extends CustomPainter {
  const _StripedProgressPainter({
    required this.value,
    required this.color,
    this.pulse = 0,
  });

  final double value;
  final Color color;
  final double pulse;

  @override
  void paint(Canvas canvas, Size size) {
    final breathe = math.sin(math.pi * pulse);
    final radius = Radius.circular(size.height / 2);
    canvas.drawRRect(
      RRect.fromRectAndRadius(Offset.zero & size, radius),
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5 + 0.4 * breathe
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
      Paint()..color = Color.lerp(color, Colors.white, 0.12 * breathe)!,
    );
    final stripe = Paint()..color = Colors.white.withValues(alpha: 0.22);
    // 静止脉冲时斜纹平移一格，看起来像荧光笔又被划了一下。
    final phase = pulse * 13;
    for (
      double x = -size.height + phase;
      x < fillWidth + size.height;
      x += 13
    ) {
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
      oldDelegate.value != value ||
      oldDelegate.color != color ||
      oldDelegate.pulse != pulse;
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
