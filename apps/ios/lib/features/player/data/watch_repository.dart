import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _deviceIdKey = 'shangan.player.device_id';

/// 设备标识仅用于区分观看会话，属于非敏感偏好并持久化在 SharedPreferences。
Future<String> loadOrCreatePlayerDeviceId() async {
  final preferences = await SharedPreferences.getInstance();
  final existing = preferences.getString(_deviceIdKey);
  if (existing != null && existing.isNotEmpty) return existing;
  final random = Random.secure();
  final suffix = List.generate(
    4,
    (_) => random.nextInt(0x100000000).toRadixString(16).padLeft(8, '0'),
  ).join();
  final created = 'ios-$suffix';
  await preferences.setString(_deviceIdKey, created);
  return created;
}

/// 创建观看会话后服务端返回的播放票据和可信续播位置。
final class WatchSessionData {
  const WatchSessionData({
    required this.sessionId,
    required this.ticketUri,
    required this.trustedPositionMs,
    required this.durationMs,
    required this.heartbeatIntervalSeconds,
    this.review = false,
  });

  final String sessionId;
  final Uri ticketUri;
  final int trustedPositionMs;
  final int durationMs;
  final int heartbeatIntervalSeconds;
  final bool review;
}

/// 心跳请求仅提交播放器事实，不能直接声明视频完成。
final class WatchHeartbeatCommand {
  const WatchHeartbeatCommand({
    required this.sequence,
    required this.positionMs,
    required this.playing,
    required this.foreground,
    required this.playbackSpeed,
  });

  final int sequence;
  final int positionMs;
  final bool playing;
  final bool foreground;
  final double playbackSpeed;

  Map<String, dynamic> toJson() => {
    'sequence': sequence,
    'positionMs': positionMs,
    'playing': playing,
    'foreground': foreground,
    'playbackSpeed': playbackSpeed,
  };
}

/// 服务端心跳裁决，包含纠偏位置、验活指令和完成状态。
final class WatchHeartbeatData {
  const WatchHeartbeatData({
    required this.trustedPositionMs,
    required this.verifiedWatchMs,
    required this.seekAllowed,
    required this.aliveCheckRequired,
    required this.completed,
    required this.status,
  });

  final int trustedPositionMs;
  final int verifiedWatchMs;
  final bool seekAllowed;
  final bool aliveCheckRequired;
  final bool completed;
  final String status;

  factory WatchHeartbeatData.fromJson(Map<String, dynamic> json) =>
      WatchHeartbeatData(
        trustedPositionMs: (json['trustedPositionMs'] as num).toInt(),
        verifiedWatchMs: (json['verifiedWatchMs'] as num).toInt(),
        seekAllowed: json['seekAllowed'] as bool,
        aliveCheckRequired: json['aliveCheckRequired'] as bool,
        completed: json['completed'] as bool,
        status: json['status'] as String,
      );
}

abstract interface class WatchRepository {
  Future<WatchSessionData> createSession(String lessonId, {String? planItemId});

  Future<WatchHeartbeatData> heartbeat(
    String sessionId,
    WatchHeartbeatCommand command,
  );

  Future<WatchHeartbeatData> confirmAliveCheck(String sessionId);

  Future<void> stop(String sessionId);
}

/// 仅通过统一 ApiClient 调用上岸服务端，不直接访问 Emby 或 Dio。
final class RemoteWatchRepository implements WatchRepository {
  factory RemoteWatchRepository({
    required ApiClient api,
    required String baseUrl,
    required String deviceId,
  }) => RemoteWatchRepository._(api, Uri.parse(baseUrl), deviceId);

  RemoteWatchRepository._(this._api, this._baseUri, this._deviceId);

  final ApiClient _api;
  final Uri _baseUri;
  final String _deviceId;

  @override
  Future<WatchSessionData> createSession(
    String lessonId, {
    String? planItemId,
  }) async {
    final json = await _api.postJson(
      '/api/v1/lessons/$lessonId/watch-sessions',
      data: {'planItemId': planItemId, 'deviceId': _deviceId},
    );
    return WatchSessionData(
      sessionId: json['sessionId'] as String,
      ticketUri: _baseUri.resolve(json['ticketUrl'] as String),
      trustedPositionMs: (json['trustedPositionMs'] as num).toInt(),
      durationMs: (json['durationMs'] as num).toInt(),
      heartbeatIntervalSeconds: (json['heartbeatIntervalSeconds'] as num)
          .toInt(),
      review: json['review'] as bool? ?? false,
    );
  }

  @override
  Future<WatchHeartbeatData> heartbeat(
    String sessionId,
    WatchHeartbeatCommand command,
  ) async => WatchHeartbeatData.fromJson(
    await _api.postJson(
      '/api/v1/watch-sessions/$sessionId/heartbeat',
      data: command.toJson(),
    ),
  );

  @override
  Future<WatchHeartbeatData> confirmAliveCheck(String sessionId) async =>
      WatchHeartbeatData.fromJson(
        await _api.postJson('/api/v1/watch-sessions/$sessionId/alive-check'),
      );

  @override
  Future<void> stop(String sessionId) async {
    await _api.postJson('/api/v1/watch-sessions/$sessionId/stop');
  }
}

final watchRepositoryProvider = Provider<WatchRepository>((ref) {
  throw StateError('WatchRepository 尚未注入');
});
