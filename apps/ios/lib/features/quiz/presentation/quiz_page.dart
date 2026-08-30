import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/features/quiz/data/quiz_repository.dart';
import 'package:shangan_ios/features/quiz/presentation/quiz_result_page.dart';

/// 视频完成后的答题页；只有全部启用题目均已选择时才允许提交。
final class QuizPage extends ConsumerStatefulWidget {
  const QuizPage({required this.lessonId, this.planItemId, super.key});

  final String lessonId;
  final String? planItemId;

  @override
  ConsumerState<QuizPage> createState() => _QuizPageState();
}

final class _QuizPageState extends ConsumerState<QuizPage> {
  late final Future<QuizData> _quiz;
  final Map<String, String> _answers = {};
  final Stopwatch _elapsed = Stopwatch()..start();
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _quiz = ref.read(quizRepositoryProvider).loadQuiz(widget.lessonId);
  }

  @override
  void dispose() {
    _elapsed.stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('课后答题')),
      body: FutureBuilder<QuizData>(
        future: _quiz,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Center(child: Text('题目暂时无法加载，请确认视频已完成后重试。'));
          }
          final quiz = snapshot.data!;
          if (quiz.questions.isEmpty) {
            return const Center(child: Text('本课没有启用的课后题，观看完成即视为任务完成。'));
          }
          final complete = _answers.length == quiz.questions.length;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text('共 ${quiz.questions.length} 题，提交后可重复作答。'),
              const SizedBox(height: 12),
              ...quiz.questions.indexed.map(
                (entry) => Card(
                  margin: const EdgeInsets.only(bottom: 12),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 8,
                          ),
                          child: Text(
                            '${entry.$1 + 1}. ${entry.$2.content}',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ),
                        RadioGroup<String>(
                          groupValue: _answers[entry.$2.id],
                          onChanged: _submitting
                              ? (_) {}
                              : (value) {
                                  if (value == null) return;
                                  setState(() => _answers[entry.$2.id] = value);
                                },
                          child: Column(
                            children: entry.$2.options
                                .map(
                                  (option) => RadioListTile<String>(
                                    value: option.id,
                                    enabled: !_submitting,
                                    title: Text(option.content),
                                  ),
                                )
                                .toList(),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 4),
              FilledButton(
                key: const Key('submitQuiz'),
                onPressed: complete && !_submitting
                    ? () => _submit(quiz)
                    : null,
                child: Text(_submitting ? '提交中…' : '提交答卷'),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _submit(QuizData quiz) async {
    setState(() => _submitting = true);
    try {
      final result = await ref
          .read(quizRepositoryProvider)
          .submit(
            widget.lessonId,
            planItemId: widget.planItemId,
            durationMs: _elapsed.elapsedMilliseconds,
            answers: Map.unmodifiable(_answers),
          );
      if (!mounted) return;
      await Navigator.of(context).pushReplacement(
        MaterialPageRoute<void>(builder: (_) => QuizResultPage(result: result)),
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('答卷提交失败，请检查网络后重试。')));
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}
