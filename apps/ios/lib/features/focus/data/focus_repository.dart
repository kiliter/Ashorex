import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 服务端专注会话快照。客户端只能展示服务端累计值，不能上传完成秒数。
final class FocusSessionData {
  const FocusSessionData({
    required this.id,
    required this.planItemId,
    required this.mediaItemId,
    required this.focusType,
    required this.status,
    required this.plannedSeconds,
    required this.actualSeconds,
    required this.startedAt,
    required this.runningSince,
    required this.pausedAt,
    required this.endedAt,
    required this.serverNow,
  });

  final String id;
  final String? planItemId;
  final String? mediaItemId;
  final String focusType;
  final String status;
  final int plannedSeconds;
  final int actualSeconds;
  final DateTime startedAt;
  final DateTime? runningSince;
  final DateTime? pausedAt;
  final DateTime? endedAt;
  final DateTime serverNow;

  factory FocusSessionData.fromJson(Map<String, dynamic> json) {
    DateTime? optionalTime(String key) {
      final value = json[key] as String?;
      return value == null ? null : DateTime.parse(value);
    }

    return FocusSessionData(
      id: json['id'] as String,
      planItemId: json['planItemId'] as String?,
      mediaItemId: json['mediaItemId'] as String?,
      focusType: json['focusType'] as String,
      status: json['status'] as String,
      plannedSeconds: (json['plannedSeconds'] as num).toInt(),
      actualSeconds: (json['actualSeconds'] as num).toInt(),
      startedAt: DateTime.parse(json['startedAt'] as String),
      runningSince: optionalTime('runningSince'),
      pausedAt: optionalTime('pausedAt'),
      endedAt: optionalTime('endedAt'),
      serverNow: DateTime.parse(json['serverNow'] as String),
    );
  }
}

abstract interface class FocusRepository {
  Future<FocusSessionData?> loadActive();

  Future<FocusSessionData> start({
    String? planItemId,
    String? mediaItemId,
    required String focusType,
    required int plannedSeconds,
  });

  Future<FocusSessionData> pause(String sessionId);

  Future<FocusSessionData> resume(String sessionId);

  Future<FocusSessionData> finish(String sessionId);

  Future<FocusSessionData> cancel(String sessionId);
}

/// 专注接口实现；所有状态变化都是显式服务端命令。
final class RemoteFocusRepository implements FocusRepository {
  RemoteFocusRepository(this._api);

  final ApiClient _api;

  @override
  Future<FocusSessionData?> loadActive() async {
    final json = await _api.getOptionalJson('/api/v1/focus-sessions/active');
    return json == null ? null : FocusSessionData.fromJson(json);
  }

  @override
  Future<FocusSessionData> start({
    String? planItemId,
    String? mediaItemId,
    required String focusType,
    required int plannedSeconds,
  }) async => FocusSessionData.fromJson(
    await _api.postJson(
      '/api/v1/focus-sessions',
      data: {
        'planItemId': planItemId,
        'mediaItemId': mediaItemId,
        'focusType': focusType,
        'plannedSeconds': plannedSeconds,
      },
    ),
  );

  @override
  Future<FocusSessionData> pause(String sessionId) =>
      _command(sessionId, 'pause');

  @override
  Future<FocusSessionData> resume(String sessionId) =>
      _command(sessionId, 'resume');

  @override
  Future<FocusSessionData> finish(String sessionId) =>
      _command(sessionId, 'finish');

  @override
  Future<FocusSessionData> cancel(String sessionId) =>
      _command(sessionId, 'cancel');

  Future<FocusSessionData> _command(String sessionId, String action) async =>
      FocusSessionData.fromJson(
        await _api.postJson('/api/v1/focus-sessions/$sessionId/$action'),
      );
}

final focusRepositoryProvider = Provider<FocusRepository>((ref) {
  throw StateError('FocusRepository 尚未注入');
});
