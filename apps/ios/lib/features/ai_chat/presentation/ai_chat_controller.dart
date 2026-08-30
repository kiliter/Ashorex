import 'package:flutter/foundation.dart';
import 'package:shangan_ios/features/ai_chat/data/ai_chat_repository.dart';
import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';

/// 通用与视频问答共用控制器；流进行中禁止第二次发送，并保留失败前的部分文本。
final class AiChatController extends ChangeNotifier {
  AiChatController({
    required this.repository,
    required this.scope,
    this.lessonId,
    this.currentPositionMs = 0,
  });

  final AiChatRepository repository;
  final ChatScope scope;
  final String? lessonId;
  int currentPositionMs;

  Conversation? conversation;
  List<ChatMessage> messages = const [];
  bool loading = true;
  bool streaming = false;
  String? errorMessage;
  int _localSequence = 0;

  Future<void> initialize() async {
    try {
      conversation = await repository.createConversation(
        scope,
        lessonId: lessonId,
      );
      messages = await repository.loadMessages(conversation!.id);
    } catch (_) {
      errorMessage = '暂时无法打开 AI 对话，请稍后重试。';
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  Future<void> send(String rawText) async {
    final text = rawText.trim();
    if (streaming || text.isEmpty || conversation == null) return;
    streaming = true;
    errorMessage = null;
    messages = [
      ...messages,
      ChatMessage(
        id: 'local-user-${_localSequence++}',
        role: 'USER',
        content: text,
        status: 'COMPLETED',
      ),
    ];
    notifyListeners();
    String? assistantId;
    try {
      await for (final event in repository.sendMessage(
        conversation!.id,
        text,
        currentPositionMs: currentPositionMs,
      )) {
        switch (event.type) {
          case 'message_start':
            assistantId = event.data['messageId'] as String;
            messages = [
              ...messages,
              ChatMessage(
                id: assistantId,
                role: 'ASSISTANT',
                content: '',
                status: 'STREAMING',
              ),
            ];
          case 'delta':
            _updateAssistant(
              assistantId,
              contentDelta: event.data['text'] as String? ?? '',
            );
          case 'citation':
            _updateAssistant(
              assistantId,
              citation: ChatCitation.fromJson(event.data),
            );
          case 'message_end':
            _updateAssistant(assistantId, status: 'COMPLETED');
          case 'error':
            _updateAssistant(assistantId, status: 'FAILED');
            errorMessage = event.data['message'] as String? ?? 'AI 回答中断，请重试。';
        }
        notifyListeners();
      }
    } catch (_) {
      _updateAssistant(assistantId, status: 'FAILED');
      errorMessage = 'AI 连接中断，已保留收到的内容。';
    } finally {
      streaming = false;
      notifyListeners();
    }
  }

  void _updateAssistant(
    String? id, {
    String contentDelta = '',
    ChatCitation? citation,
    String? status,
  }) {
    if (id == null) return;
    messages = messages.map((message) {
      if (message.id != id) return message;
      return ChatMessage(
        id: message.id,
        role: message.role,
        content: message.content + contentDelta,
        status: status ?? message.status,
        citations: [...message.citations, ?citation],
      );
    }).toList();
  }
}
