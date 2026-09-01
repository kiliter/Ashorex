import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
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
            return const ShanganLoading('正在读取课后题');
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
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        ShanganEyebrow('课后答题 · ${quiz.questions.length} 题'),
                        const SizedBox(height: 6),
                        Text(
                          '基础检查',
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                      ],
                    ),
                  ),
                  Text(
                    '${_answers.length}/${quiz.questions.length}',
                    style: shanganNumberStyle(context, fontSize: 16),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              ShanganProgress(value: _answers.length / quiz.questions.length),
              const SizedBox(height: 20),
              const Divider(color: ShanganColors.ink),
              ...quiz.questions.indexed.map(
                (entry) => Container(
                  padding: const EdgeInsets.symmetric(vertical: 18),
                  decoration: const BoxDecoration(
                    border: Border(
                      bottom: BorderSide(color: ShanganColors.rule),
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      ShanganEyebrow(
                        '${(entry.$1 + 1).toString().padLeft(2, '0')} · '
                        '${entry.$2.questionType == 'TRUE_FALSE' ? '判断题' : '单选题'}',
                      ),
                      const SizedBox(height: 6),
                      Text(
                        entry.$2.content,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 8),
                      RadioGroup<String>(
                        groupValue: _answers[entry.$2.id],
                        onChanged: _submitting
                            ? (_) {}
                            : (value) {
                                if (value == null) return;
                                setState(() => _answers[entry.$2.id] = value);
                              },
                        child: Column(
                          children: entry.$2.options.indexed.map((optionEntry) {
                            final selected =
                                _answers[entry.$2.id] == optionEntry.$2.id;
                            return Padding(
                              padding: const EdgeInsets.only(top: 8),
                              child: Material(
                                color: selected
                                    ? ShanganColors.blueSoft
                                    : ShanganColors.surface,
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(11),
                                  side: BorderSide(
                                    color: selected
                                        ? ShanganColors.ink
                                        : ShanganColors.rule,
                                  ),
                                ),
                                clipBehavior: Clip.antiAlias,
                                child: RadioListTile<String>(
                                  value: optionEntry.$2.id,
                                  enabled: !_submitting,
                                  activeColor: ShanganColors.ink,
                                  title: Text(optionEntry.$2.content),
                                  secondary: Text(
                                    String.fromCharCode(65 + optionEntry.$1),
                                    style: shanganNumberStyle(
                                      context,
                                      fontSize: 12,
                                    ),
                                  ),
                                ),
                              ),
                            );
                          }).toList(),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              if (!complete) ...[
                const SizedBox(height: 14),
                Text(
                  '◇ 还有 ${quiz.questions.length - _answers.length} 题未完成，全部回答后才能提交。',
                  style: const TextStyle(color: ShanganColors.red),
                ),
              ],
              const SizedBox(height: 18),
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
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('答卷提交失败，请检查网络后重试。')));
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}
