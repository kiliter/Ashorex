import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/features/ai_chat/data/ai_chat_repository.dart';
import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';
import 'package:shangan_ios/features/ai_chat/presentation/ai_chat_controller.dart';
import 'package:shangan_ios/features/ai_chat/presentation/citation_list.dart';

/// 视频页 AI 弹层；转写未 READY 时明确降级为普通问答，不伪装成视频内容回答。
final class VideoAiSheet extends ConsumerStatefulWidget {
  const VideoAiSheet({
    required this.lessonId,
    required this.currentPosition,
    this.onVideoSeek,
    super.key,
  });

  final String lessonId;
  final Duration currentPosition;
  final ValueChanged<Duration>? onVideoSeek;

  @override
  ConsumerState<VideoAiSheet> createState() => _VideoAiSheetState();
}

final class _VideoAiSheetState extends ConsumerState<VideoAiSheet> {
  late final AiChatController _controller;
  late final Future<LessonAiStatus> _status;
  final _input = TextEditingController();

  @override
  void initState() {
    super.initState();
    final repository = ref.read(aiChatRepositoryProvider);
    _status = repository.loadLessonStatus(widget.lessonId);
    _controller = AiChatController(
      repository: repository,
      scope: ChatScope.video,
      lessonId: widget.lessonId,
      currentPositionMs: widget.currentPosition.inMilliseconds,
    )..addListener(_changed);
    unawaited(_controller.initialize());
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _controller.removeListener(_changed);
    _controller.dispose();
    _input.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.82,
        child: Column(
          children: [
            const SizedBox(height: 8),
            Container(
              width: 36,
              height: 5,
              decoration: BoxDecoration(
                color: Colors.black26,
                borderRadius: BorderRadius.circular(3),
              ),
            ),
            const ListTile(
              title: Text(
                '问这节课',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              subtitle: Text('只读问答 · 回答中的时间码可返回视频位置'),
            ),
            FutureBuilder<LessonAiStatus>(
              future: _status,
              builder: (context, snapshot) {
                if (snapshot.hasData && !snapshot.data!.ready) {
                  return const ListTile(
                    key: Key('transcriptProcessingNotice'),
                    leading: Icon(Icons.hourglass_top),
                    title: Text('视频内容仍在处理中'),
                    subtitle: Text('现在可以问普通问题，但回答不会引用本视频内容。'),
                  );
                }
                return const SizedBox.shrink();
              },
            ),
            Expanded(
              child: _controller.loading
                  ? const Center(child: CircularProgressIndicator())
                  : ListView.builder(
                      padding: const EdgeInsets.all(16),
                      itemCount: _controller.messages.length,
                      itemBuilder: (context, index) {
                        final message = _controller.messages[index];
                        final mine = message.role == 'USER';
                        return Align(
                          alignment: mine
                              ? Alignment.centerRight
                              : Alignment.centerLeft,
                          child: Container(
                            constraints: const BoxConstraints(maxWidth: 340),
                            margin: const EdgeInsets.only(bottom: 10),
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                              color: mine
                                  ? const Color(0xFF193B63)
                                  : const Color(0xFFF2F4F7),
                              borderRadius: BorderRadius.circular(14),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                Text(
                                  message.content.isEmpty
                                      ? '正在思考…'
                                      : message.content,
                                  style: TextStyle(
                                    color: mine ? Colors.white : Colors.black87,
                                  ),
                                ),
                                if (!mine)
                                  CitationList(
                                    citations: message.citations,
                                    onVideoSeek: widget.onVideoSeek,
                                  ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
            ),
            if (_controller.errorMessage case final error?)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Text(
                  error,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            Padding(
              padding: EdgeInsets.fromLTRB(
                12,
                8,
                12,
                MediaQuery.viewInsetsOf(context).bottom + 8,
              ),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      key: const Key('videoAiInput'),
                      controller: _input,
                      enabled: !_controller.streaming,
                      minLines: 1,
                      maxLines: 4,
                      decoration: const InputDecoration(
                        hintText: '输入问题',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filled(
                    key: const Key('videoAiSend'),
                    tooltip: '发送问题',
                    constraints: const BoxConstraints.tightFor(
                      width: 48,
                      height: 48,
                    ),
                    onPressed: _controller.streaming
                        ? null
                        : () {
                            final text = _input.text;
                            _input.clear();
                            unawaited(_controller.send(text));
                          },
                    icon: const Icon(Icons.arrow_upward),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
