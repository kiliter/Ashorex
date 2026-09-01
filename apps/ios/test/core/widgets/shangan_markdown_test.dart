import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_markdown.dart';

void main() {
  testWidgets('AI 摘要按 Markdown 结构渲染而不是显示原始标记', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ShanganTheme.light(),
        home: const Scaffold(
          body: SingleChildScrollView(
            child: ShanganMarkdown(
              data: '# 核心结论\n\n- 第一项\n- **重点内容**\n\n> 复习提示',
            ),
          ),
        ),
      ),
    );

    expect(find.byType(MarkdownBody), findsOneWidget);
    expect(find.textContaining('核心结论', findRichText: true), findsOneWidget);
    expect(find.textContaining('重点内容', findRichText: true), findsOneWidget);
    expect(find.textContaining('复习提示', findRichText: true), findsOneWidget);
    expect(find.textContaining('# 核心结论', findRichText: true), findsNothing);
  });
}
