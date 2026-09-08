import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';

void main() {
  testWidgets('亮色主题使用白纸墨水配色，通用组件可正常渲染', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ShanganTheme.light(),
        home: const Scaffold(
          body: Padding(
            padding: EdgeInsets.all(20),
            child: ShanganProgress(value: 0.44),
          ),
        ),
      ),
    );

    final context = tester.element(find.byType(ShanganProgress));
    expect(Theme.of(context).scaffoldBackgroundColor, ShanganColors.paper);
    expect(find.byType(ShanganProgress), findsOneWidget);
  });
}
