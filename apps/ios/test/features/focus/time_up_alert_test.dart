import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/focus/presentation/time_up_alert.dart';

void main() {
  testWidgets('到时弹窗展示闹钟动画并在确认后关闭', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => showTimeUpAlert(context, message: '专注时间已到'),
            child: const Text('ring'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('ring'));
    await tester.pump();
    expect(find.byKey(const Key('timeUpAlertDialog')), findsOneWidget);
    expect(find.text('时间到了'), findsOneWidget);
    expect(find.text('专注时间已到'), findsOneWidget);

    await tester.tap(find.byKey(const Key('dismissTimeUpAlert')));
    await tester.pump();
    expect(find.byKey(const Key('timeUpAlertDialog')), findsNothing);
  });
}
