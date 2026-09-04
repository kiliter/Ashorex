import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// 到时提醒：不可点外部关闭，闹钟震动动画并循环系统铃声，确认后停止。
Future<void> showTimeUpAlert(BuildContext context, {required String message}) {
  return showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (_) => TimeUpAlertDialog(message: message),
  );
}

final class TimeUpAlertDialog extends StatefulWidget {
  const TimeUpAlertDialog({required this.message, super.key});

  final String message;

  @override
  State<TimeUpAlertDialog> createState() => _TimeUpAlertDialogState();
}

final class _TimeUpAlertDialogState extends State<TimeUpAlertDialog>
    with SingleTickerProviderStateMixin {
  late final AnimationController _shake;
  Timer? _ringer;

  @override
  void initState() {
    super.initState();
    _shake = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 160),
    )..repeat(reverse: true);
    _ringer = Timer.periodic(const Duration(milliseconds: 900), (_) => _ring());
    _ring();
  }

  /// 用系统提示音和触感模拟闹钟，避免引入额外音频资源。
  void _ring() {
    SystemSound.play(SystemSoundType.alert);
    HapticFeedback.heavyImpact();
  }

  @override
  void dispose() {
    _ringer?.cancel();
    _shake.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      key: const Key('timeUpAlertDialog'),
      icon: AnimatedBuilder(
        animation: _shake,
        builder: (context, child) {
          final wobble = math.sin(_shake.value * math.pi) * 10;
          return Transform.translate(
            offset: Offset(wobble, 0),
            child: Transform.rotate(angle: wobble * 0.04, child: child),
          );
        },
        child: const Icon(Icons.alarm, size: 56, color: ShanganColors.ochre),
      ),
      title: const Text('时间到了'),
      content: Text(widget.message),
      actions: [
        FilledButton(
          key: const Key('dismissTimeUpAlert'),
          onPressed: () => Navigator.pop(context),
          child: const Text('确定'),
        ),
      ],
    );
  }
}
