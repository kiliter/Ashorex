import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';

void main() {
  testWidgets('高保真主题使用白纸墨水配色和可信刻度组件', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ShanganTheme.light(),
        home: const Scaffold(
          body: Padding(
            padding: EdgeInsets.all(20),
            child: ShanganTrustScale(
              label: '可信进度',
              valueLabel: '可信至 18:40 / 42:00',
              trustedFraction: 0.44,
              positionFraction: 0.56,
              thresholdFraction: 0.9,
            ),
          ),
        ),
      ),
    );

    final context = tester.element(find.byType(ShanganTrustScale));
    expect(Theme.of(context).scaffoldBackgroundColor, ShanganColors.paper);
    expect(find.text('可信进度'), findsOneWidget);
    expect(find.text('已验证'), findsOneWidget);
    expect(find.text('尚不可跳转'), findsOneWidget);
  });
}
