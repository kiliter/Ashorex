import 'package:flutter/material.dart';
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
        padding: const EdgeInsets.all(16),
        children: [
          Semantics(
            header: true,
            child: Text(
              '得分 ${result.score}',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
          ),
          const SizedBox(height: 4),
          Text('答对 ${result.correctCount} / ${result.totalCount} 题'),
          const SizedBox(height: 16),
          ...result.answers.indexed.map(
            (entry) => Card(
              child: ListTile(
                leading: Icon(
                  entry.$2.correct ? Icons.check_circle : Icons.cancel,
                ),
                title: Text(
                  '第 ${entry.$1 + 1} 题：${entry.$2.correct ? '回答正确' : '回答错误'}',
                ),
                subtitle: Text(
                  entry.$2.explanation.isEmpty
                      ? '本题暂无解析。'
                      : '解析：${entry.$2.explanation}',
                ),
              ),
            ),
          ),
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
