import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// 视频进度仅用于展示，普通学习与复习均通过按钮定位，避免拖拽直接跳过整段内容。
final class PlayerTimeline extends StatelessWidget {
  const PlayerTimeline({
    required this.duration,
    required this.position,
    required this.maximumSeek,
    super.key,
  });

  final Duration duration;
  final Duration position;
  final Duration maximumSeek;

  @override
  Widget build(BuildContext context) {
    final durationMs = duration.inMilliseconds.toDouble().clamp(
      1.0,
      double.infinity,
    );
    final maximumMs = maximumSeek.inMilliseconds.toDouble().clamp(
      0.0,
      durationMs,
    );
    // 心跳之间实际播放会领先通过边界，滑块仍应展示真实位置，不能倒退到旧边界。
    final value = position.inMilliseconds.toDouble().clamp(0.0, durationMs);
    return Semantics(
      label: '视频进度',
      value: '${position.inSeconds} 秒 / ${duration.inSeconds} 秒',
      readOnly: true,
      child: SizedBox(
        height: 44,
        child: SliderTheme(
          data: SliderTheme.of(context).copyWith(
            disabledActiveTrackColor: Colors.white,
            disabledInactiveTrackColor: Colors.white38,
            disabledSecondaryActiveTrackColor: ShanganColors.blue,
            disabledThumbColor: Colors.white,
            trackHeight: 3,
          ),
          child: Slider(
            value: value,
            secondaryTrackValue: maximumMs >= value ? maximumMs : null,
            min: 0,
            max: durationMs,
            onChanged: null,
          ),
        ),
      ),
    );
  }
}
