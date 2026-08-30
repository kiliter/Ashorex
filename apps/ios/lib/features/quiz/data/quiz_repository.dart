import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 客户端题目选项不包含正确标记，避免在提交前泄露答案。
final class QuizOptionData {
  const QuizOptionData({required this.id, required this.content});

  final String id;
  final String content;

  factory QuizOptionData.fromJson(Map<String, dynamic> json) => QuizOptionData(
    id: json['id'] as String,
    content: json['content'] as String,
  );
}

/// 一道已解锁的课后题题面。
final class QuizQuestionData {
  const QuizQuestionData({
    required this.id,
    required this.questionType,
    required this.content,
    required this.options,
  });

  final String id;
  final String questionType;
  final String content;
  final List<QuizOptionData> options;

  factory QuizQuestionData.fromJson(Map<String, dynamic> json) =>
      QuizQuestionData(
        id: json['id'] as String,
        questionType: json['questionType'] as String,
        content: json['content'] as String,
        options: (json['options'] as List)
            .map(
              (value) => QuizOptionData.fromJson(
                Map<String, dynamic>.from(value as Map),
              ),
            )
            .toList(),
      );
}

final class QuizData {
  const QuizData({required this.mediaItemId, required this.questions});

  final String mediaItemId;
  final List<QuizQuestionData> questions;

  factory QuizData.fromJson(Map<String, dynamic> json) => QuizData(
    mediaItemId: json['mediaItemId'] as String,
    questions: (json['questions'] as List)
        .map(
          (value) => QuizQuestionData.fromJson(
            Map<String, dynamic>.from(value as Map),
          ),
        )
        .toList(),
  );
}

/// 提交后才返回的逐题正确性与解析。
final class QuizAnswerResultData {
  const QuizAnswerResultData({
    required this.questionId,
    required this.selectedOptionId,
    required this.correct,
    required this.explanation,
  });

  final String questionId;
  final String selectedOptionId;
  final bool correct;
  final String explanation;

  factory QuizAnswerResultData.fromJson(Map<String, dynamic> json) =>
      QuizAnswerResultData(
        questionId: json['questionId'] as String,
        selectedOptionId: json['selectedOptionId'] as String,
        correct: json['correct'] as bool,
        explanation: json['explanation'] as String? ?? '',
      );
}

final class QuizAttemptResultData {
  const QuizAttemptResultData({
    required this.id,
    required this.score,
    required this.correctCount,
    required this.totalCount,
    required this.answers,
  });

  final String id;
  final int score;
  final int correctCount;
  final int totalCount;
  final List<QuizAnswerResultData> answers;

  factory QuizAttemptResultData.fromJson(Map<String, dynamic> json) =>
      QuizAttemptResultData(
        id: json['id'] as String,
        score: (json['score'] as num).toInt(),
        correctCount: (json['correctCount'] as num).toInt(),
        totalCount: (json['totalCount'] as num).toInt(),
        answers: (json['answers'] as List)
            .map(
              (value) => QuizAnswerResultData.fromJson(
                Map<String, dynamic>.from(value as Map),
              ),
            )
            .toList(),
      );
}

abstract interface class QuizRepository {
  Future<QuizData> loadQuiz(String lessonId);

  Future<QuizAttemptResultData> submit(
    String lessonId, {
    String? planItemId,
    required int durationMs,
    required Map<String, String> answers,
  });
}

/// 题目完整性和判分由服务端负责，客户端只发送用户选择。
final class RemoteQuizRepository implements QuizRepository {
  RemoteQuizRepository(this._api);

  final ApiClient _api;

  @override
  Future<QuizData> loadQuiz(String lessonId) async =>
      QuizData.fromJson(await _api.getJson('/api/v1/lessons/$lessonId/quiz'));

  @override
  Future<QuizAttemptResultData> submit(
    String lessonId, {
    String? planItemId,
    required int durationMs,
    required Map<String, String> answers,
  }) async {
    final perAnswerDuration = answers.isEmpty
        ? 0
        : durationMs ~/ answers.length;
    return QuizAttemptResultData.fromJson(
      await _api.postJson(
        '/api/v1/lessons/$lessonId/quiz-attempts',
        data: {
          'planItemId': planItemId,
          'durationMs': durationMs,
          'answers': answers.entries
              .map(
                (entry) => {
                  'questionId': entry.key,
                  'selectedOptionId': entry.value,
                  'durationMs': perAnswerDuration,
                },
              )
              .toList(),
        },
      ),
    );
  }
}

final quizRepositoryProvider = Provider<QuizRepository>((ref) {
  throw StateError('QuizRepository 尚未注入');
});
