import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// 统一渲染服务端只读 Markdown，供课程摘要和播放器摘要复用。
final class ShanganMarkdown extends StatelessWidget {
  const ShanganMarkdown({required this.data, super.key});

  final String data;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final body = textTheme.bodyMedium?.copyWith(height: 1.65);
    return MarkdownBody(
      data: data,
      selectable: true,
      softLineBreak: true,
      styleSheet: MarkdownStyleSheet(
        p: body,
        h1: textTheme.headlineSmall?.copyWith(fontSize: 20),
        h1Padding: const EdgeInsets.only(top: 4, bottom: 8),
        h2: textTheme.titleLarge?.copyWith(fontSize: 17),
        h2Padding: const EdgeInsets.only(top: 12, bottom: 6),
        h3: textTheme.titleMedium,
        h3Padding: const EdgeInsets.only(top: 10, bottom: 4),
        h4: textTheme.bodyLarge?.copyWith(fontWeight: FontWeight.w700),
        h5: textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
        h6: textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w700),
        strong: const TextStyle(fontWeight: FontWeight.w700),
        em: const TextStyle(fontStyle: FontStyle.italic),
        a: const TextStyle(
          color: ShanganColors.blue,
          decoration: TextDecoration.underline,
          decorationColor: ShanganColors.blue,
        ),
        code: body?.copyWith(
          height: 1.45,
          fontFamily: 'SF Mono',
          fontFamilyFallback: const ['Menlo', 'monospace'],
          fontSize: 12,
          backgroundColor: ShanganColors.inkSoft,
        ),
        listBullet: body?.copyWith(
          color: ShanganColors.blue,
          fontWeight: FontWeight.w700,
        ),
        listIndent: 22,
        blockSpacing: 10,
        blockquote: body?.copyWith(color: ShanganColors.mutedInk),
        blockquotePadding: const EdgeInsets.fromLTRB(12, 8, 10, 8),
        blockquoteDecoration: const BoxDecoration(
          color: ShanganColors.blueSoft,
          border: Border(left: BorderSide(color: ShanganColors.blue, width: 3)),
        ),
        codeblockPadding: const EdgeInsets.all(12),
        codeblockDecoration: BoxDecoration(
          color: ShanganColors.inkSoft,
          border: Border.all(color: ShanganColors.rule),
          borderRadius: BorderRadius.circular(10),
        ),
        tableHead: textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
        tableBody: body,
        tableBorder: TableBorder.all(color: ShanganColors.rule),
        tableCellsPadding: const EdgeInsets.symmetric(
          horizontal: 10,
          vertical: 7,
        ),
        horizontalRuleDecoration: const BoxDecoration(
          border: Border(
            top: BorderSide(color: ShanganColors.rule, width: 1.5),
          ),
        ),
      ),
    );
  }
}
