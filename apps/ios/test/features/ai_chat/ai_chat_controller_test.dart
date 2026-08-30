import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/ai_chat/data/ai_chat_repository.dart';
import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';
import 'package:shangan_ios/features/ai_chat/presentation/ai_chat_controller.dart';

void main() {
  test('分片、引用与结束事件合并成一条完整助手消息', () async {
    final repository = _FakeAiRepository();
    final controller = AiChatController(
      repository: repository,
      scope: ChatScope.general,
    );
    await controller.initialize();

    await controller.send('你好');

    expect(controller.streaming, isFalse);
    expect(controller.messages, hasLength(2));
    expect(controller.messages.last.content, '增长率答案');
    expect(controller.messages.last.status, 'COMPLETED');
    expect(
      controller.messages.last.citations.single.url,
      'https://example.test',
    );
  });
}

final class _FakeAiRepository implements AiChatRepository {
  @override
  Future<Conversation> createConversation(
    ChatScope scope, {
    String? lessonId,
  }) async => Conversation(
    id: 'conversation-1',
    scope: scope,
    title: '测试',
    lessonId: lessonId,
  );

  @override
  Future<List<ChatMessage>> loadMessages(String conversationId) async =>
      const [];

  @override
  Stream<AiStreamEvent> sendMessage(
    String conversationId,
    String text, {
    int currentPositionMs = 0,
  }) async* {
    yield const AiStreamEvent('message_start', {'messageId': 'assistant-1'});
    yield const AiStreamEvent('delta', {'text': '增长率'});
    yield const AiStreamEvent('delta', {'text': '答案'});
    yield const AiStreamEvent('citation', {
      'type': 'WEB',
      'title': '来源',
      'url': 'https://example.test',
      'positionMs': null,
    });
    yield const AiStreamEvent('message_end', {});
  }

  @override
  Future<LessonAiStatus> loadLessonStatus(String lessonId) async =>
      const LessonAiStatus(status: 'READY', videoContextAvailable: true);
}
