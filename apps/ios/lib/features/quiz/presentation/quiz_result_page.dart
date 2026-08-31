import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/quiz/data/quiz_repository.dart';

/// 展示本次成绩、逐题正误与服务端解析，不使用颜色作为唯一状态表达。
final class QuizResultPage extends StatelessWidget {
  const QuizResultPage({required this.result, super.key});

  final QuizAttemptResultData result;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('答题结果')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
        children: [
          const ShanganEyebrow('本次答题结果'),
          const SizedBox(height: 8),
          Semantics(
            header: true,
            child: Text(
              '得分 ${result.score}',
              style: shanganNumberStyle(context, fontSize: 34),
            ),
          ),
          const SizedBox(height: 4),
          Text('答对 ${result.correctCount} / ${result.totalCount} 题'),
          const SizedBox(height: 16),
          const Divider(color: ShanganColors.ink),
          ...result.answers.indexed.map((entry) {
            final correct = entry.$2.correct;
            return Container(
              padding: const EdgeInsets.symmetric(vertical: 16),
              decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: ShanganColors.rule)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(child: ShanganEyebrow('第 ${entry.$1 + 1} 题')),
                      ShanganStatusTag(
                        correct ? '✓ 回答正确' : '× 回答错误',
                        tone: correct
                            ? ShanganTagTone.success
                            : ShanganTagTone.risk,
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(
                    entry.$2.explanation.isEmpty
                        ? '本题暂无解析。'
                        : '解析：${entry.$2.explanation}',
                  ),
                ],
              ),
            );
          }),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: () =>
                Navigator.of(context).popUntil((route) => route.isFirst),
            child: const Text('完成'),
          ),
        ],
      ),
    );
  }
}
