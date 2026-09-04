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

/// 把分钟数格式化为「x 小时 y 分钟」。
String formatFocusDurationLabel(int minutes) {
  final hours = minutes ~/ 60;
  final remainder = minutes % 60;
  if (hours == 0) return '$remainder 分钟';
  if (remainder == 0) return '$hours 小时';
  return '$hours 小时 $remainder 分钟';
}

/// 弹出时长选择：点预设立即开始，也可拖动横向滑条自定义。
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

/// 预设列表加横向滑条自定义，首页弹层和专注页共用。
final class FocusDurationPicker extends StatefulWidget {
  const FocusDurationPicker({required this.onSelected, super.key});

  final ValueChanged<int> onSelected;

  @override
  State<FocusDurationPicker> createState() => _FocusDurationPickerState();
}

final class _FocusDurationPickerState extends State<FocusDurationPicker> {
  int _customMinutes = 30;

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
              '可点预设，也可拖动滑条自定义；选定后立即开始倒计时。',
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
          FocusDurationSlider(
            minutes: _customMinutes,
            onChanged: (minutes) => setState(() => _customMinutes = minutes),
          ),
          const SizedBox(height: 4),
          Text(
            formatFocusDurationLabel(_customMinutes),
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

/// 横向拖拽调节 1–720 分钟。
///
/// 使用绝对位置映射，不做增量累加，因此任意幅度的连续拖动都能持续生效。拖动时在滑条上方浮起
/// 一个跟随滑块的指示器，带弹性入场、数字滚动切换和轨道加粗动画。
final class FocusDurationSlider extends StatefulWidget {
  const FocusDurationSlider({
    required this.minutes,
    required this.onChanged,
    super.key,
  });

  final int minutes;
  final ValueChanged<int> onChanged;

  @override
  State<FocusDurationSlider> createState() => _FocusDurationSliderState();
}

final class _FocusDurationSliderState extends State<FocusDurationSlider> {
  static const _bubbleHeight = 44.0;
  static const _bubbleGap = 8.0;

  bool _dragging = false;

  /// 当前值在 0（最短）到 1（最长）之间的比例，用于定位浮起指示器。
  double get _ratio =>
      (widget.minutes - focusCustomDurationMinMinutes) /
      (focusCustomDurationMaxMinutes - focusCustomDurationMinMinutes);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            height: _bubbleHeight + _bubbleGap,
            child: _dragging
                ? Align(
                    // 比例映射到 -1..1，使指示器水平跟随滑块。
                    alignment: Alignment(_ratio * 2 - 1, -1),
                    child: _DurationBubble(minutes: widget.minutes),
                  )
                : null,
          ),
          // 拖动时轨道加粗并提高滑块外圈，给出明确的操作反馈。
          AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            curve: Curves.easeOut,
            height: _dragging ? 48 : 40,
            alignment: Alignment.center,
            child: SliderTheme(
              data: SliderTheme.of(context).copyWith(
                trackHeight: _dragging ? 10 : 6,
                activeTrackColor: ShanganColors.blue,
                inactiveTrackColor: ShanganColors.rule.withValues(alpha: 0.45),
                thumbColor: ShanganColors.blue,
                overlayColor: ShanganColors.blue.withValues(alpha: 0.14),
                thumbShape: RoundSliderThumbShape(
                  enabledThumbRadius: _dragging ? 14 : 11,
                ),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 24),
                showValueIndicator: ShowValueIndicator.never,
              ),
              child: Slider(
                key: const Key('focusCustomDurationSlider'),
                min: focusCustomDurationMinMinutes.toDouble(),
                max: focusCustomDurationMaxMinutes.toDouble(),
                value: widget.minutes.toDouble().clamp(
                  focusCustomDurationMinMinutes.toDouble(),
                  focusCustomDurationMaxMinutes.toDouble(),
                ),
                // 无障碍读屏与 Dynamic Type 场景下播报可读时长而不是原始数字。
                semanticFormatterCallback: (value) =>
                    formatFocusDurationLabel(value.round()),
                onChangeStart: (_) => setState(() => _dragging = true),
                onChanged: (value) {
                  final next = value.round().clamp(
                    focusCustomDurationMinMinutes,
                    focusCustomDurationMaxMinutes,
                  );
                  if (next != widget.minutes) widget.onChanged(next);
                },
                onChangeEnd: (_) => setState(() => _dragging = false),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 拖动时浮起的时长指示器：弹性放大入场，数字变化时上下滚动切换。
final class _DurationBubble extends StatefulWidget {
  const _DurationBubble({required this.minutes});

  final int minutes;

  @override
  State<_DurationBubble> createState() => _DurationBubbleState();
}

final class _DurationBubbleState extends State<_DurationBubble>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    duration: const Duration(milliseconds: 260),
    vsync: this,
  )..forward();

  late final Animation<double> _scale = CurvedAnimation(
    parent: _controller,
    curve: Curves.elasticOut,
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final label = formatFocusDurationLabel(widget.minutes);
    return ScaleTransition(
      key: const Key('focusCustomDurationBubble'),
      scale: _scale,
      alignment: Alignment.bottomCenter,
      child: FadeTransition(
        opacity: _controller,
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [ShanganColors.blue, Color(0xFF3F6FD8)],
            ),
            borderRadius: BorderRadius.circular(14),
            boxShadow: [
              BoxShadow(
                color: ShanganColors.blue.withValues(alpha: 0.32),
                blurRadius: 16,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 140),
              transitionBuilder: (child, animation) => ClipRect(
                child: SlideTransition(
                  position: Tween<Offset>(
                    begin: const Offset(0, 0.6),
                    end: Offset.zero,
                  ).animate(animation),
                  child: FadeTransition(opacity: animation, child: child),
                ),
              ),
              child: Text(
                label,
                key: ValueKey<String>(label),
                style: shanganNumberStyle(
                  context,
                  fontSize: 18,
                ).copyWith(color: ShanganColors.surface),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
