import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_chat_core/flutter_chat_core.dart' as chat_core;
import 'package:flutter_chat_ui/flutter_chat_ui.dart' hide ChatMessage;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/features/ai_chat/data/ai_chat_repository.dart';
import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';
import 'package:shangan_ios/features/ai_chat/presentation/ai_chat_controller.dart';
import 'package:shangan_ios/features/ai_chat/presentation/citation_list.dart';

/// 首页独立只读 AI Tab；视觉采用“学习批注页”，不暗示 AI 能替用户执行操作。
final class AiTabPage extends ConsumerStatefulWidget {
  const AiTabPage({super.key});

  @override
  ConsumerState<AiTabPage> createState() => _AiTabPageState();
}

final class _AiTabPageState extends ConsumerState<AiTabPage> {
  late final AiChatController _controller;
  final _chat = chat_core.InMemoryChatController();

  @override
  void initState() {
    super.initState();
    _controller = AiChatController(
      repository: ref.read(aiChatRepositoryProvider),
      scope: ChatScope.general,
    )..addListener(_sync);
    unawaited(_controller.initialize());
  }

  Future<void> _sync() async {
    if (!mounted) return;
    await _chat.setMessages(
      _controller.messages.map<chat_core.Message>(_toUiMessage).toList(),
      animated: false,
    );
    setState(() {});
  }

  chat_core.TextMessage _toUiMessage(ChatMessage message) =>
      chat_core.TextMessage(
        id: message.id,
        authorId: message.role == 'USER' ? 'me' : 'assistant',
        text: message.content.isEmpty ? '正在思考…' : message.content,
        metadata: {'citations': message.citations, 'status': message.status},
      );

  @override
  void dispose() {
    _controller.removeListener(_sync);
    _controller.dispose();
    _chat.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('学习问答'),
        bottom: const PreferredSize(
          preferredSize: Size.fromHeight(30),
          child: Padding(
            padding: EdgeInsets.only(bottom: 8),
            child: Text('只读助手 · 不会修改计划、欠债或学习记录'),
          ),
        ),
      ),
      body: _controller.loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                if (_controller.errorMessage case final error?)
                  MaterialBanner(
                    content: Text(error),
                    actions: const [SizedBox.shrink()],
                  ),
                Expanded(
                  child: Chat(
                    currentUserId: 'me',
                    resolveUser: (id) async => chat_core.User(id: id),
                    chatController: _chat,
                    onMessageSend: _controller.streaming
                        ? null
                        : _controller.send,
                    backgroundColor: const Color(0xFFF7F8FA),
                    builders: chat_core.Builders(
                      textMessageBuilder: _messageBuilder,
                    ),
                  ),
                ),
              ],
            ),
    );
  }

  Widget _messageBuilder(
    BuildContext context,
    chat_core.TextMessage message,
    int index, {
    required bool isSentByMe,
    chat_core.MessageGroupStatus? groupStatus,
  }) {
    final citations =
        (message.metadata?['citations'] as List<ChatCitation>?) ?? const [];
    return Align(
      alignment: isSentByMe ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        constraints: const BoxConstraints(maxWidth: 340),
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: isSentByMe ? const Color(0xFF193B63) : Colors.white,
          borderRadius: BorderRadius.circular(16),
          border: isSentByMe
              ? null
              : Border.all(color: const Color(0xFFD9E0E8)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              message.text,
              style: TextStyle(
                color: isSentByMe ? Colors.white : const Color(0xFF17212B),
              ),
            ),
            if (!isSentByMe) CitationList(citations: citations),
          ],
        ),
      ),
    );
  }
}
