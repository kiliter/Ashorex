import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/planning/presentation/abandon_plan_sheet.dart';

void main() {
  testWidgets('开摆确认展示精确新增欠债且确认后才允许提交', (tester) async {
    var submitted = false;
    const preview = AbandonPreviewData(
      debtCount: 2,
      addedDebtSeconds: 1300,
      debts: [
        DebtPreviewData(type: 'VIDEO_WATCH', title: '资料分析', seconds: 700),
        DebtPreviewData(type: 'QUIZ', title: '资料分析', seconds: 600),
      ],
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AbandonPlanSheet(
            preview: preview,
            onConfirm: (_) async => submitted = true,
          ),
        ),
      ),
    );

    expect(find.textContaining('新增 1300 秒欠债'), findsOneWidget);
    expect(find.text('VIDEO_WATCH · 700 秒'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('confirmAbandon')))
          .onPressed,
      isNull,
    );

    await tester.tap(find.byType(CheckboxListTile));
    await tester.pump();
    await tester.tap(find.byKey(const Key('confirmAbandon')));
    await tester.pump();
    expect(submitted, isTrue);
  });
}
