import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 用户的一场考试目标。
final class ExamGoal {
  const ExamGoal({
    required this.id,
    required this.name,
    required this.examDate,
    required this.targetCompletionDate,
    required this.reviewBufferDays,
    required this.timezone,
    required this.courseIds,
  });

  final String id;
  final String name;
  final DateTime examDate;
  final DateTime targetCompletionDate;
  final int reviewBufferDays;
  final String timezone;
  final List<String> courseIds;

  factory ExamGoal.fromJson(Map<String, dynamic> json) => ExamGoal(
    id: json['id'] as String,
    name: json['name'] as String,
    examDate: DateTime.parse(json['examDate'] as String),
    targetCompletionDate: DateTime.parse(
      json['targetCompletionDate'] as String,
    ),
    reviewBufferDays: (json['reviewBufferDays'] as num).toInt(),
    timezone: json['timezone'] as String,
    courseIds: List<String>.from(json['courseIds'] as List),
  );
}

/// 保存考试目标所需的用户输入。
final class ExamGoalDraft {
  const ExamGoalDraft({
    required this.name,
    required this.examDate,
    required this.targetCompletionDate,
    required this.reviewBufferDays,
    required this.courseIds,
  });

  final String name;
  final DateTime examDate;
  final DateTime targetCompletionDate;
  final int reviewBufferDays;
  final List<String> courseIds;
}

abstract interface class ExamRepository {
  Future<ExamGoal?> loadGoal();

  Future<List<ExamGoal>> listGoals();

  Future<ExamGoal> loadGoalById(String goalId);

  Future<ExamGoal> saveGoal(ExamGoalDraft draft);

  Future<ExamGoal> updateGoal(String goalId, ExamGoalDraft draft);
}

/// 考试页面只通过上岸服务端读写目标，不在客户端计算业务真相。
final class RemoteExamRepository implements ExamRepository {
  RemoteExamRepository(this._api);

  final ApiClient _api;

  @override
  Future<ExamGoal?> loadGoal() async {
    final json = await _api.getOptionalJson('/api/v1/exam-goal');
    return json == null ? null : ExamGoal.fromJson(json);
  }

  @override
  Future<List<ExamGoal>> listGoals() async {
    return (await _api.getJsonList(
      '/api/v1/exam-goals',
    )).map(ExamGoal.fromJson).toList();
  }

  @override
  Future<ExamGoal> loadGoalById(String goalId) async =>
      ExamGoal.fromJson(await _api.getJson('/api/v1/exam-goals/$goalId'));

  Map<String, dynamic> _draftJson(ExamGoalDraft draft) => {
    'name': draft.name,
    'examDate': _date(draft.examDate),
    'targetCompletionDate': _date(draft.targetCompletionDate),
    'reviewBufferDays': draft.reviewBufferDays,
    'courseIds': draft.courseIds,
  };

  @override
  Future<ExamGoal> saveGoal(ExamGoalDraft draft) async {
    return ExamGoal.fromJson(
      await _api.putJson('/api/v1/exam-goal', data: _draftJson(draft)),
    );
  }

  @override
  Future<ExamGoal> updateGoal(String goalId, ExamGoalDraft draft) async {
    return ExamGoal.fromJson(
      await _api.putJson('/api/v1/exam-goals/$goalId', data: _draftJson(draft)),
    );
  }

  String _date(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-'
      '${value.month.toString().padLeft(2, '0')}-'
      '${value.day.toString().padLeft(2, '0')}';
}

final examRepositoryProvider = Provider<ExamRepository>((ref) {
  throw StateError('ExamRepository 尚未注入');
});
