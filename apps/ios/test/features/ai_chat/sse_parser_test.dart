import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/ai_chat/data/sse_parser.dart';
import 'package:shangan_ios/features/ai_chat/domain/chat_models.dart';

void main() {
  test('任意网络分片仍按事件边界增量拼出你好', () async {
    const source =
        'event: delta\ndata: {"text":"你"}\n\n'
        'event: delta\ndata: {"text":"好"}\n\n';
    final bytes = utf8.encode(source);
    final chunks = <List<int>>[
      bytes.sublist(0, 5),
      bytes.sublist(5, 19),
      bytes.sublist(19, 37),
      bytes.sublist(37),
    ];

    final events = await SseParser()
        .parse(Stream.fromIterable(chunks))
        .toList();

    expect(events.map((event) => event.type), ['delta', 'delta']);
    expect(events.map((event) => event.data['text']).join(), '你好');
  });

  test('畸形 JSON 转换为安全解析错误', () async {
    final stream = Stream.value(
      utf8.encode('event: delta\ndata: not-json\n\n'),
    );

    expect(
      SseParser().parse(stream).drain<void>(),
      throwsA(isA<SseParseException>()),
    );
  });
}
