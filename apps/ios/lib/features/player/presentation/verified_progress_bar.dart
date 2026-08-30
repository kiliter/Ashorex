import 'package:flutter/material.dart';

/// 展示完整视频时长，但任何拖动目标都限制在服务端可信最大位置以内。
final class VerifiedProgressBar extends StatefulWidget {
  const VerifiedProgressBar({
    required this.duration,
    required this.position,
    required this.maxVerifiedPosition,
    required this.onSeek,
    super.key,
  });

  final Duration duration;
  final Duration position;
  final Duration maxVerifiedPosition;
  final Future<void> Function(Duration position) onSeek;

  @override
  State<VerifiedProgressBar> createState() => _VerifiedProgressBarState();
}

final class _VerifiedProgressBarState extends State<VerifiedProgressBar> {
  double? _dragValue;

  double get _durationMs =>
      widget.duration.inMilliseconds.toDouble().clamp(1, double.infinity);

  double get _trustedMs => widget.maxVerifiedPosition.inMilliseconds
      .toDouble()
      .clamp(0, _durationMs);

  @override
  Widget build(BuildContext context) {
    final value =
        _dragValue ??
        widget.position.inMilliseconds.toDouble().clamp(0, _trustedMs);
    return Semantics(
      label: '可信学习进度',
      value:
          '${_format(widget.position)}，最多可回看到 ${_format(widget.maxVerifiedPosition)}',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Stack(
            alignment: Alignment.center,
            children: [
              LinearProgressIndicator(
                minHeight: 6,
                value: _trustedMs / _durationMs,
                backgroundColor: Theme.of(context)
                    .colorScheme
                    .surfaceContainerHighest,
              ),
              SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  activeTrackColor: Colors.transparent,
                  inactiveTrackColor: Colors.transparent,
                  trackHeight: 6,
                ),
                child: Slider(
                  value: value,
                  min: 0,
                  max: _durationMs,
                  onChanged: (candidate) {
                    setState(() => _dragValue = candidate.clamp(0, _trustedMs));
                  },
                  onChangeEnd: (candidate) async {
                    final safe = candidate.clamp(0, _trustedMs).round();
                    setState(() => _dragValue = null);
                    await widget.onSeek(Duration(milliseconds: safe));
                  },
                ),
              ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(_format(widget.position)),
                Text(
                  '可信至 ${_format(widget.maxVerifiedPosition)} / ${_format(widget.duration)}',
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _format(Duration value) {
    final minutes = value.inMinutes;
    final seconds = value.inSeconds.remainder(60);
    return '$minutes:${seconds.toString().padLeft(2, '0')}';
  }
}
