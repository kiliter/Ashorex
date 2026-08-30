import 'dart:convert';

import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';

/// 按 UTF-8 字节增量解析 SSE；网络分片可以出现在任意字符或事件边界。
final class SseParser {
  Stream<AiStreamEvent> parse(Stream<List<int>> bytes) async* {
    // Dio 常返回 Uint8List；先复制为 List<int>，避免运行时泛型导致 decoder 转换失败。
    final lines = bytes
        .map<List<int>>((chunk) => List<int>.from(chunk))
        .transform(utf8.decoder)
        .transform(const LineSplitter());
    String? eventName;
    final dataLines = <String>[];
    await for (final line in lines) {
      if (line.isEmpty) {
        if (eventName != null || dataLines.isNotEmpty) {
          yield _build(eventName, dataLines);
          eventName = null;
          dataLines.clear();
        }
        continue;
      }
      if (line.startsWith(':')) continue;
      if (line.startsWith('event:')) {
        eventName = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.add(line.substring(5).trimLeft());
      }
    }
    if (eventName != null || dataLines.isNotEmpty) {
      yield _build(eventName, dataLines);
    }
  }

  AiStreamEvent _build(String? eventName, List<String> dataLines) {
    if (eventName == null || eventName.isEmpty || dataLines.isEmpty) {
      throw const SseParseException('SSE 事件缺少 event 或 data');
    }
    try {
      final decoded = jsonDecode(dataLines.join('\n'));
      if (decoded is! Map) throw const FormatException();
      return AiStreamEvent(eventName, Map<String, dynamic>.from(decoded));
    } on FormatException {
      throw const SseParseException('SSE data 不是有效 JSON 对象');
    }
  }
}
