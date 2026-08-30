import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/features/ai_chat/data/sse_parser.dart';
import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';

abstract interface class AiChatRepository {
  Future<Conversation> createConversation(ChatScope scope, {String? lessonId});

  Future<List<ChatMessage>> loadMessages(String conversationId);

  Stream<AiStreamEvent> sendMessage(
    String conversationId,
    String text, {
    int currentPositionMs = 0,
  });

  Future<LessonAiStatus> loadLessonStatus(String lessonId);
}

/// AI API 远端实现；屏幕和控制器均不直接依赖 Dio。
final class RemoteAiChatRepository implements AiChatRepository {
  RemoteAiChatRepository(this._api, {SseParser? parser})
    : _parser = parser ?? SseParser();

  final ApiClient _api;
  final SseParser _parser;

  @override
  Future<Conversation> createConversation(
    ChatScope scope, {
    String? lessonId,
  }) async => Conversation.fromJson(
    await _api.postJson(
      '/api/v1/ai/conversations',
      data: {
        'scope': scope == ChatScope.video ? 'VIDEO' : 'GENERAL',
        'mediaItemId': lessonId,
      },
    ),
  );

  @override
  Future<List<ChatMessage>> loadMessages(String conversationId) async {
    final rows = await _api.getJsonList(
      '/api/v1/ai/conversations/$conversationId/messages',
    );
    return rows.map(_message).toList();
  }

  @override
  Stream<AiStreamEvent> sendMessage(
    String conversationId,
    String text, {
    int currentPositionMs = 0,
  }) async* {
    final bytes = await _api.postByteStream(
      '/api/v1/ai/conversations/$conversationId/messages:stream',
      data: {'content': text, 'currentPositionMs': currentPositionMs},
    );
    yield* _parser.parse(bytes);
  }

  @override
  Future<LessonAiStatus> loadLessonStatus(String lessonId) async {
    final json = await _api.getJson('/api/v1/lessons/$lessonId/ai-status');
    return LessonAiStatus(
      status: json['status'] as String,
      videoContextAvailable: json['videoContextAvailable'] as bool,
    );
  }

  ChatMessage _message(Map<String, dynamic> row) {
    final raw = row['citationsJson'] as String? ?? '[]';
    final decoded = jsonDecode(raw) as List<dynamic>;
    return ChatMessage(
      id: row['id'] as String,
      role: row['role'] as String,
      content: row['content'] as String,
      status: row['status'] as String,
      citations: decoded
          .map(
            (value) =>
                ChatCitation.fromJson(Map<String, dynamic>.from(value as Map)),
          )
          .toList(),
    );
  }
}

final aiChatRepositoryProvider = Provider<AiChatRepository>((ref) {
  throw StateError('AiChatRepository 尚未注入');
});
