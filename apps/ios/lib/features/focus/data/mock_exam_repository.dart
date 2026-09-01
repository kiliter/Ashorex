import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 系统设置中可复用的模拟考试名称和时长预置。
final class MockExamPresetData {
  const MockExamPresetData({
    required this.id,
    required this.name,
    required this.durationSeconds,
    required this.sortOrder,
  });

  final String id;
  final String name;
  final int durationSeconds;
  final int sortOrder;

  factory MockExamPresetData.fromJson(Map<String, dynamic> json) =>
      MockExamPresetData(
        id: json['id'] as String,
        name: json['name'] as String,
        durationSeconds: (json['durationSeconds'] as num).toInt(),
        sortOrder: (json['sortOrder'] as num?)?.toInt() ?? 0,
      );
}

final class MockExamAttachmentData {
  const MockExamAttachmentData({required this.id, required this.filename});

  final String id;
  final String filename;

  factory MockExamAttachmentData.fromJson(Map<String, dynamic> json) =>
      MockExamAttachmentData(
        id: json['id'] as String,
        filename: json['originalFilename'] as String,
      );
}

/// 模拟考试会话使用服务端截止时间；客户端倒计时只是该快照的展示。
final class MockExamSessionData {
  const MockExamSessionData({
    required this.id,
    required this.planItemId,
    required this.name,
    required this.status,
    required this.deadlineAt,
    required this.serverNow,
    required this.attachments,
  });

  final String id;
  final String planItemId;
  final String name;
  final String status;
  final DateTime deadlineAt;
  final DateTime serverNow;
  final List<MockExamAttachmentData> attachments;

  factory MockExamSessionData.fromSessionJson(
    Map<String, dynamic> json, {
    List<MockExamAttachmentData> attachments = const [],
  }) => MockExamSessionData(
    id: json['id'] as String,
    planItemId: json['planItemId'] as String,
    name: json['name'] as String,
    status: json['status'] as String,
    deadlineAt: DateTime.parse(json['deadlineAt'] as String),
    serverNow: DateTime.parse(json['serverNow'] as String),
    attachments: attachments,
  );

  factory MockExamSessionData.fromDetailsJson(Map<String, dynamic> json) {
    final attachments = (json['attachments'] as List<dynamic>? ?? const [])
        .map(
          (item) => MockExamAttachmentData.fromJson(
            Map<String, dynamic>.from(item as Map),
          ),
        )
        .toList();
    return MockExamSessionData.fromSessionJson(
      Map<String, dynamic>.from(json['session'] as Map),
      attachments: attachments,
    );
  }
}

abstract interface class MockExamRepository {
  Future<List<MockExamPresetData>> listPresets();
  Future<MockExamPresetData> createPreset(String name, int seconds);
  Future<MockExamPresetData> updatePreset(
    MockExamPresetData preset,
    String name,
    int seconds,
  );
  Future<void> deletePreset(String id);
  Future<MockExamSessionData> start(String planItemId);
  Future<MockExamSessionData> load(String sessionId);
  Future<MockExamSessionData> submitEarly(String sessionId);
  Future<MockExamSessionData> upload(
    String sessionId,
    String filename,
    List<int> bytes,
  );
}

final class RemoteMockExamRepository implements MockExamRepository {
  RemoteMockExamRepository(this._api);

  final ApiClient _api;

  @override
  Future<List<MockExamPresetData>> listPresets() async =>
      (await _api.getJsonList('/api/v1/mock-exam-presets'))
          .map(MockExamPresetData.fromJson)
          .toList();

  @override
  Future<MockExamPresetData> createPreset(String name, int seconds) async =>
      MockExamPresetData.fromJson(
        await _api.postJson(
          '/api/v1/mock-exam-presets',
          data: {'name': name, 'durationSeconds': seconds, 'sortOrder': 0},
        ),
      );

  @override
  Future<MockExamPresetData> updatePreset(
    MockExamPresetData preset,
    String name,
    int seconds,
  ) async => MockExamPresetData.fromJson(
    await _api.putJson(
      '/api/v1/mock-exam-presets/${preset.id}',
      data: {
        'name': name,
        'durationSeconds': seconds,
        'sortOrder': preset.sortOrder,
      },
    ),
  );

  @override
  Future<void> deletePreset(String id) =>
      _api.deleteEmpty('/api/v1/mock-exam-presets/$id');

  @override
  Future<MockExamSessionData> start(String planItemId) async =>
      MockExamSessionData.fromSessionJson(
        await _api.postJson('/api/v1/mock-exams/$planItemId/start'),
      );

  @override
  Future<MockExamSessionData> load(String sessionId) async =>
      MockExamSessionData.fromDetailsJson(
        await _api.getJson('/api/v1/mock-exams/$sessionId'),
      );

  @override
  Future<MockExamSessionData> submitEarly(String sessionId) async =>
      MockExamSessionData.fromSessionJson(
        await _api.postJson('/api/v1/mock-exams/$sessionId/submit-early'),
      );

  @override
  Future<MockExamSessionData> upload(
    String sessionId,
    String filename,
    List<int> bytes,
  ) async => MockExamSessionData.fromDetailsJson(
    await _api.postFile(
      '/api/v1/mock-exams/$sessionId/attachments',
      fieldName: 'file',
      filename: filename,
      bytes: bytes,
    ),
  );
}

final mockExamRepositoryProvider = Provider<MockExamRepository>((ref) {
  throw StateError('MockExamRepository 尚未注入');
});
