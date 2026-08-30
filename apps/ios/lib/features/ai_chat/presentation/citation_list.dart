import 'package:flutter/material.dart';
import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';

/// 回答下方的学习批注条；视频时间码和网页来源始终以文字显示，不只依赖颜色。
final class CitationList extends StatelessWidget {
  const CitationList({required this.citations, this.onVideoSeek, super.key});

  final List<ChatCitation> citations;
  final ValueChanged<Duration>? onVideoSeek;

  @override
  Widget build(BuildContext context) {
    if (citations.isEmpty) return const SizedBox.shrink();
    return Column(
      key: const Key('citationList'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Divider(height: 16),
        for (final citation in citations)
          InkWell(
            onTap: citation.positionMs == null || onVideoSeek == null
                ? null
                : () => onVideoSeek!(
                    Duration(milliseconds: citation.positionMs!),
                  ),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  Icon(
                    citation.type == 'VIDEO'
                        ? Icons.play_circle_outline
                        : Icons.link,
                    size: 18,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      citation.positionMs == null
                          ? citation.title
                          : '${_time(citation.positionMs!)} · ${citation.title}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }

  String _time(int milliseconds) {
    final seconds = milliseconds ~/ 1000;
    return '${(seconds ~/ 60).toString().padLeft(2, '0')}:${(seconds % 60).toString().padLeft(2, '0')}';
  }
}
