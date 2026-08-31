import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
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
            const ShanganEyebrow('高风险操作 · 不可撤销', color: ShanganColors.red),
            const SizedBox(height: 7),
            Text('确认开摆', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            ShanganNotice(
              title: '新增 ${widget.preview.addedDebtSeconds} 秒欠债',
              message:
                  '约 ${shanganDuration(widget.preview.addedDebtSeconds)}。确认后立即结束今日学习，且无法撤销。',
              tone: ShanganTagTone.risk,
            ),
            const SizedBox(height: 14),
            ...widget.preview.debts.map(
              (debt) => Container(
                padding: const EdgeInsets.symmetric(vertical: 10),
                decoration: const BoxDecoration(
                  border: Border(bottom: BorderSide(color: ShanganColors.rule)),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            debt.title,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          Text(
                            '${debt.type} · ${debt.seconds} 秒',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                    Text(
                      shanganDuration(debt.seconds),
                      style: shanganNumberStyle(context, fontSize: 12),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: _reason,
              minLines: 2,
              maxLines: 4,
              decoration: const InputDecoration(
                labelText: '开摆原因',
                hintText: '请记录具体原因，晚间复盘会引用',
              ),
            ),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('我知道开摆不可撤销，剩余任务会形成欠债'),
              value: _confirmedConsequence,
              onChanged: (value) =>
                  setState(() => _confirmedConsequence = value == true),
            ),
            FilledButton(
              key: const Key('confirmAbandon'),
              onPressed: _confirmedConsequence
                  ? () => widget.onConfirm(_reason.text)
                  : null,
              style: FilledButton.styleFrom(
                backgroundColor: ShanganColors.red,
                shadowColor: ShanganColors.red,
              ),
              child: const Text('确认开摆并记入欠债'),
            ),
          ],
        ),
      ),
    );
  }
}
