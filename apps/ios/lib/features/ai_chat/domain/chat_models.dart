/// AI 会话范围；视频范围只影响服务端上下文，不授予任何写权限。
enum ChatScope { general, video }

final class Conversation {
  const Conversation({
    required this.id,
    required this.scope,
    required this.title,
    this.lessonId,
  });

  final String id;
  final ChatScope scope;
  final String title;
  final String? lessonId;

  factory Conversation.fromJson(Map<String, dynamic> json) => Conversation(
    id: json['id'] as String,
    scope: json['scope'] == 'VIDEO' ? ChatScope.video : ChatScope.general,
    title: json['title'] as String,
    lessonId: json['mediaItemId'] as String?,
  );
}

final class ChatCitation {
  const ChatCitation({
    required this.type,
    required this.title,
    this.url,
    this.positionMs,
  });

  final String type;
  final String title;
  final String? url;
  final int? positionMs;

  factory ChatCitation.fromJson(Map<String, dynamic> json) => ChatCitation(
    type: json['type'] as String,
    title: json['title'] as String,
    url: json['url'] as String?,
    positionMs: (json['positionMs'] as num?)?.toInt(),
  );
}

final class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.role,
    required this.content,
    required this.status,
    this.citations = const [],
  });

  final String id;
  final String role;
  final String content;
  final String status;
  final List<ChatCitation> citations;
}

final class AiStreamEvent {
  const AiStreamEvent(this.type, this.data);

  final String type;
  final Map<String, dynamic> data;
}

final class SseParseException implements Exception {
  const SseParseException(this.message);

  final String message;

  @override
  String toString() => 'SseParseException: $message';
}

final class LessonAiStatus {
  const LessonAiStatus({
    required this.status,
    required this.videoContextAvailable,
  });

  final String status;
  final bool videoContextAvailable;

  bool get ready => status == 'READY' && videoContextAvailable;
}
