import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';

/// 独立专注的快捷预设：15 分钟、30 分钟、1 小时、2 小时。
const focusDurationPresets = <({String label, int seconds})>[
  (label: '15 分钟', seconds: 15 * 60),
  (label: '30 分钟', seconds: 30 * 60),
  (label: '1 小时', seconds: 60 * 60),
  (label: '2 小时', seconds: 2 * 60 * 60),
];

const focusCustomDurationMinMinutes = 1;
const focusCustomDurationMaxMinutes = 720;

/// 弹出时长选择：点预设立即开始，也可滑动圆形选择器自定义。
Future<int?> showFocusDurationSheet(BuildContext context) {
  return showModalBottomSheet<int>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (sheetContext) => Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.viewInsetsOf(sheetContext).bottom,
      ),
      child: SafeArea(
        child: FocusDurationPicker(
          onSelected: (seconds) => Navigator.pop(sheetContext, seconds),
        ),
      ),
    ),
  );
}

/// 预设列表加圆形滑动自定义，首页弹层和专注页共用。
final class FocusDurationPicker extends StatefulWidget {
  const FocusDurationPicker({required this.onSelected, super.key});

  final ValueChanged<int> onSelected;

  @override
  State<FocusDurationPicker> createState() => _FocusDurationPickerState();
}

final class _FocusDurationPickerState extends State<FocusDurationPicker> {
  int _customMinutes = 30;

  String get _customLabel {
    final hours = _customMinutes ~/ 60;
    final minutes = _customMinutes % 60;
    if (hours == 0) return '$minutes 分钟';
    if (minutes == 0) return '$hours 小时';
    return '$hours 小时 $minutes 分钟';
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 0, 8, 4),
            child: Text(
              '选择专注时长',
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
            child: Text(
              '可点预设，也可沿圆环滑动自定义；选定后立即开始倒计时。',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
          for (final preset in focusDurationPresets)
            ListTile(
              key: Key('focusDuration-${preset.seconds}'),
              minTileHeight: 56,
              title: Text(preset.label),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => widget.onSelected(preset.seconds),
            ),
          const Divider(color: ShanganColors.rule),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 8, 8, 8),
            child: Text('自定义', style: Theme.of(context).textTheme.titleMedium),
          ),
          FocusCircularDurationDial(
            minutes: _customMinutes,
            onChanged: (minutes) => setState(() => _customMinutes = minutes),
          ),
          const SizedBox(height: 8),
          Text(
            _customLabel,
            key: const Key('focusCustomDurationLabel'),
            textAlign: TextAlign.center,
            style: shanganNumberStyle(context, fontSize: 22),
          ),
          const SizedBox(height: 16),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: FilledButton(
              key: const Key('startCustomFocusDuration'),
              onPressed: () => widget.onSelected(_customMinutes * 60),
              child: const Text('开始自定义时长'),
            ),
          ),
        ],
      ),
    );
  }
}

/// 沿圆环滑动调节 1–720 分钟：一整圈对应 60 分钟。
final class FocusCircularDurationDial extends StatefulWidget {
  const FocusCircularDurationDial({
    required this.minutes,
    required this.onChanged,
    super.key,
  });

  final int minutes;
  final ValueChanged<int> onChanged;

  @override
  State<FocusCircularDurationDial> createState() =>
      _FocusCircularDurationDialState();
}

final class _FocusCircularDurationDialState
    extends State<FocusCircularDurationDial> {
  double? _lastAngle;

  double _angleAt(Offset local, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final vector = local - center;
    return math.atan2(vector.dx, -vector.dy);
  }

  void _onPanStart(DragStartDetails details, Size size) {
    _lastAngle = _angleAt(details.localPosition, size);
  }

  void _onPanUpdate(DragUpdateDetails details, Size size) {
    final angle = _angleAt(details.localPosition, size);
    final previous = _lastAngle;
    _lastAngle = angle;
    if (previous == null) return;
    var delta = angle - previous;
    if (delta > math.pi) delta -= 2 * math.pi;
    if (delta < -math.pi) delta += 2 * math.pi;
    final next = (widget.minutes + delta / (2 * math.pi) * 60).round().clamp(
      focusCustomDurationMinMinutes,
      focusCustomDurationMaxMinutes,
    );
    if (next != widget.minutes) widget.onChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SizedBox.square(
        dimension: 252,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final size = Size(constraints.maxWidth, constraints.maxHeight);
            return GestureDetector(
              key: const Key('focusCustomDurationDial'),
              behavior: HitTestBehavior.opaque,
              onPanStart: (details) => _onPanStart(details, size),
              onPanUpdate: (details) => _onPanUpdate(details, size),
              child: CustomPaint(
                painter: _DurationDialPainter(
                  minutes: widget.minutes,
                  track: ShanganColors.rule.withValues(alpha: 0.45),
                  sweep: ShanganColors.blue,
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

final class _DurationDialPainter extends CustomPainter {
  const _DurationDialPainter({
    required this.minutes,
    required this.track,
    required this.sweep,
  });

  final int minutes;
  final Color track;
  final Color sweep;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.shortestSide / 2 - 16;
    final rect = Rect.fromCircle(center: center, radius: radius);
    final trackPaint = Paint()
      ..color = track
      ..style = PaintingStyle.stroke
      ..strokeWidth = 14
      ..strokeCap = StrokeCap.round;
    final sweepPaint = Paint()
      ..color = sweep
      ..style = PaintingStyle.stroke
      ..strokeWidth = 14
      ..strokeCap = StrokeCap.round;
    canvas.drawCircle(center, radius, trackPaint);
    final minuteAngle = (minutes % 60) / 60 * 2 * math.pi;
    canvas.drawArc(rect, -math.pi / 2, minuteAngle, false, sweepPaint);
    final thumb = Offset(
      center.dx + radius * math.sin(minuteAngle),
      center.dy - radius * math.cos(minuteAngle),
    );
    canvas.drawCircle(thumb, 12, Paint()..color = sweep);
    canvas.drawCircle(thumb, 6, Paint()..color = ShanganColors.surface);
  }

  @override
  bool shouldRepaint(covariant _DurationDialPainter oldDelegate) =>
      oldDelegate.minutes != minutes;
}
