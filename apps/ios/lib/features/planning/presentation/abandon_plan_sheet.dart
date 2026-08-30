import 'package:flutter/material.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

/// 开摆确认必须展示剩余组成部分和服务端计算的精确新增欠债。
final class AbandonPlanSheet extends StatefulWidget {
  const AbandonPlanSheet({
    required this.preview,
    required this.onConfirm,
    super.key,
  });

  final AbandonPreviewData preview;
  final Future<void> Function(String reason) onConfirm;

  @override
  State<AbandonPlanSheet> createState() => _AbandonPlanSheetState();
}

final class _AbandonPlanSheetState extends State<AbandonPlanSheet> {
  final _reason = TextEditingController();
  bool _confirmedConsequence = false;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: ListView(
          shrinkWrap: true,
          children: [
            Text('确认开摆', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            Text(
              '将立即结束今日学习，并新增 ${widget.preview.addedDebtSeconds} 秒欠债',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            ...widget.preview.debts.map(
              (debt) => ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(debt.title),
                subtitle: Text('${debt.type} · ${debt.seconds} 秒'),
              ),
            ),
            TextField(
              controller: _reason,
              decoration: const InputDecoration(labelText: '开摆原因（可选）'),
            ),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('我知道开摆不可撤销，剩余任务会形成欠债'),
              value: _confirmedConsequence,
              onChanged: (value) =>
                  setState(() => _confirmedConsequence = value == true),
            ),
            FilledButton.tonal(
              key: const Key('confirmAbandon'),
              onPressed: _confirmedConsequence
                  ? () => widget.onConfirm(_reason.text)
                  : null,
              child: const Text('确认开摆并记入欠债'),
            ),
          ],
        ),
      ),
    );
  }
}
