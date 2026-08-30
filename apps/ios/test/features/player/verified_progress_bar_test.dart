import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/player/presentation/verified_progress_bar.dart';

void main() {
  testWidgets('拖动目标始终被限制在服务端可信最大位置', (tester) async {
    Duration? target;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: VerifiedProgressBar(
            duration: const Duration(seconds: 100),
            position: const Duration(seconds: 20),
            maxVerifiedPosition: const Duration(seconds: 40),
            onSeek: (value) async => target = value,
          ),
        ),
      ),
    );

    await tester.drag(find.byType(Slider), const Offset(800, 0));
    await tester.pump();

    expect(target, isNotNull);
    expect(target, lessThanOrEqualTo(const Duration(seconds: 40)));
  });
}
