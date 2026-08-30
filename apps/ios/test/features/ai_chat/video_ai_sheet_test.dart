import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/ai_chat/data/ai_chat_repository.dart';
import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';
import 'package:shangan_ios/features/ai_chat/presentation/video_ai_sheet.dart';

void main() {
  testWidgets('转写未 READY 时提示处理中但保留普通问答输入', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          aiChatRepositoryProvider.overrideWithValue(_ProcessingRepository()),
        ],
        child: const MaterialApp(
          home: Scaffold(
            body: VideoAiSheet(
              lessonId: 'lesson-1',
              currentPosition: Duration(minutes: 3),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('transcriptProcessingNotice')), findsOneWidget);
    expect(find.text('视频内容仍在处理中'), findsOneWidget);
    expect(find.byKey(const Key('videoAiInput')), findsOneWidget);
    expect(find.byKey(const Key('videoAiSend')), findsOneWidget);
  });
}

final class _ProcessingRepository implements AiChatRepository {
  @override
  Future<Conversation> createConversation(
    ChatScope scope, {
    String? lessonId,
  }) async => Conversation(
    id: 'video-1',
    scope: scope,
    title: '视频问答',
    lessonId: lessonId,
  );

  @override
  Future<LessonAiStatus> loadLessonStatus(String lessonId) async =>
      const LessonAiStatus(
        status: 'TRANSCRIBING',
        videoContextAvailable: false,
      );

  @override
  Future<List<ChatMessage>> loadMessages(String conversationId) async =>
      const [];

  @override
  Stream<AiStreamEvent> sendMessage(
    String conversationId,
    String text, {
    int currentPositionMs = 0,
  }) => const Stream.empty();
}
