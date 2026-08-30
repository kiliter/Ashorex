import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/quiz/data/quiz_repository.dart';
import 'package:shangan_ios/features/quiz/presentation/quiz_page.dart';

void main() {
  testWidgets('完整选择后提交答卷并展示分数与逐题解析', (tester) async {
    final repository = _FakeQuizRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [quizRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(
          home: QuizPage(lessonId: 'lesson-1', planItemId: 'item-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('课后答题'), findsOneWidget);
    expect(find.textContaining('这是一道单选题'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('submitQuiz')))
          .onPressed,
      isNull,
    );

    await tester.tap(find.text('选项 B'));
    await tester.pump();
    await tester.tap(find.byKey(const Key('submitQuiz')));
    await tester.pumpAndSettle();

    expect(repository.lastPlanItemId, 'item-1');
    expect(repository.lastAnswers, {'question-1': 'option-b'});
    expect(find.text('得分 100'), findsOneWidget);
    expect(find.text('解析：因为 B 正确。'), findsOneWidget);
  });
}

final class _FakeQuizRepository implements QuizRepository {
  String? lastPlanItemId;
  Map<String, String>? lastAnswers;

  @override
  Future<QuizData> loadQuiz(String lessonId) async => const QuizData(
    mediaItemId: 'lesson-1',
    questions: [
      QuizQuestionData(
        id: 'question-1',
        questionType: 'SINGLE_CHOICE',
        content: '这是一道单选题',
        options: [
          QuizOptionData(id: 'option-a', content: '选项 A'),
          QuizOptionData(id: 'option-b', content: '选项 B'),
        ],
      ),
    ],
  );

  @override
  Future<QuizAttemptResultData> submit(
    String lessonId, {
    String? planItemId,
    required int durationMs,
    required Map<String, String> answers,
  }) async {
    lastPlanItemId = planItemId;
    lastAnswers = answers;
    return const QuizAttemptResultData(
      id: 'attempt-1',
      score: 100,
      correctCount: 1,
      totalCount: 1,
      answers: [
        QuizAnswerResultData(
          questionId: 'question-1',
          selectedOptionId: 'option-b',
          correct: true,
          explanation: '因为 B 正确。',
        ),
      ],
    );
  }
}
