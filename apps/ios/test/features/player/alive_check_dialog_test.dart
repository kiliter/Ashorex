import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/player/presentation/alive_check_dialog.dart';

void main() {
  testWidgets('验活对话框不可点击外部关闭且必须明确确认', (tester) async {
    var confirmed = false;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: FilledButton(
              onPressed: () => showDialog<void>(
                context: context,
                barrierDismissible: false,
                builder: (_) =>
                    AliveCheckDialog(onConfirm: () async => confirmed = true),
              ),
              child: const Text('打开'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();
    await tester.tapAt(const Offset(5, 5));
    await tester.pump();
    expect(find.text('还在学习吗？'), findsOneWidget);
    expect(confirmed, isFalse);

    await tester.tap(find.byKey(const Key('confirmAliveCheck')));
    await tester.pumpAndSettle();
    expect(confirmed, isTrue);
    expect(find.text('还在学习吗？'), findsNothing);
  });
}
