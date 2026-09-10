import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log_store.dart';

void main() {
  test('写满后轮转为 .1，快照包含两份文件', () async {
    final dir = await Directory.systemTemp.createTemp('diag-log-');
    addTearDown(() => dir.delete(recursive: true));
    final store = DiagnosticLogStore(directory: dir, maxBytes: 80);
    await store.open();
    store.append('first-line-aaaaaaaaaaaaaaaa');
    store.append('second-line-bbbbbbbbbbbbbbb');
    store.append('third-line-ccccccccccccccc');
    final snapshot = await store.snapshot();
    await store.close();
    expect(snapshot.isEmpty, isFalse);
    final text = String.fromCharCodes(snapshot.bytes);
    expect(text, contains('===== diagnostic.log'));
    expect(File('${dir.path}/diagnostic.log.1').existsSync(), isTrue);
  });

  test('写入前脱敏 Token', () async {
    final dir = await Directory.systemTemp.createTemp('diag-log-');
    addTearDown(() => dir.delete(recursive: true));
    final store = DiagnosticLogStore(directory: dir);
    await store.open();
    store.append('Authorization: Bearer abc.def.ghi');
    final snapshot = await store.snapshot();
    await store.close();
    final text = String.fromCharCodes(snapshot.bytes);
    expect(text, isNot(contains('abc.def.ghi')));
    expect(text, contains('[REDACTED]'));
  });
}
