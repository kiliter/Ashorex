import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';

/// 不提供取消入口的验活对话框；外层 showDialog 必须设置 barrierDismissible=false。
final class AliveCheckDialog extends StatefulWidget {
  const AliveCheckDialog({required this.onConfirm, super.key});

  final Future<void> Function() onConfirm;

  @override
  State<AliveCheckDialog> createState() => _AliveCheckDialogState();
}

final class _AliveCheckDialogState extends State<AliveCheckDialog> {
  bool _submitting = false;
  bool _confirmed = false;

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: _confirmed,
      child: AlertDialog(
        icon: const Icon(
          Icons.visibility_outlined,
          size: 36,
          color: ShanganColors.blue,
        ),
        titlePadding: const EdgeInsets.fromLTRB(24, 0, 24, 0),
        title: const Text('还在学习吗？'),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ShanganEyebrow('可信学习确认'),
            SizedBox(height: 10),
            Text('请在 60 秒内明确确认。确认前视频保持暂停，等待时间不会计入可信学习进度。'),
          ],
        ),
        actions: [
          FilledButton(
            key: const Key('confirmAliveCheck'),
            onPressed: _submitting
                ? null
                : () async {
                    setState(() => _submitting = true);
                    try {
                      await widget.onConfirm();
                      if (!context.mounted) return;
                      setState(() => _confirmed = true);
                      Navigator.of(context).pop();
                    } finally {
                      if (mounted) setState(() => _submitting = false);
                    }
                  },
            child: Text(_submitting ? '确认中…' : '我还在，继续学习'),
          ),
        ],
      ),
    );
  }
}
