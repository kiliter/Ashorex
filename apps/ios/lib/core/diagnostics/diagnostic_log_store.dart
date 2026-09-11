import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:shangan_ios/core/diagnostics/diagnostic_log_redactor.dart';

/// 本机滚动日志文件：当前 `diagnostic.log` 写满 2 MiB 后轮转为 `.1`。
///
/// 快照只读内存副本，避免 Widget 测试的 FakeAsync 里对未关闭的 [IOSink] 读盘卡住。
final class DiagnosticLogStore {
  DiagnosticLogStore({
    required this.directory,
    this.redactor = const DiagnosticLogRedactor(),
    this.maxBytes = 2 * 1024 * 1024,
  });

  final Directory directory;
  final DiagnosticLogRedactor redactor;
  final int maxBytes;

  static const fileName = 'diagnostic.log';
  static const rotatedName = 'diagnostic.log.1';

  IOSink? _sink;
  int _bytes = 0;
  Future<void> _chain = Future<void>.value();
  bool _opened = false;
  final _current = BytesBuilder(copy: false);
  final _rotated = BytesBuilder(copy: false);

  File get currentFile => File('${directory.path}/$fileName');
  File get rotatedFile => File('${directory.path}/$rotatedName');

  /// 打开或创建当前日志文件，从已有长度继续追加。
  Future<void> open() async {
    await directory.create(recursive: true);
    if (await rotatedFile.exists()) {
      _rotated.add(await rotatedFile.readAsBytes());
    }
    if (await currentFile.exists()) {
      final existing = await currentFile.readAsBytes();
      _current.add(existing);
      _bytes = existing.length;
    } else {
      _bytes = 0;
    }
    _sink = currentFile.openWrite(mode: FileMode.append);
    _opened = true;
  }

  /// 异步追加一行；写满后轮转。失败不影响业务路径。
  void append(String line) {
    if (!_opened) return;
    _chain = _chain.then((_) => _write(line)).catchError((_) {});
  }

  /// 上报用快照来自内存，不读未关闭的日志文件。
  Future<DiagnosticLogSnapshot> snapshot() async {
    await _chain;
    final chunks = <int>[];
    final rotated = _rotated.takeBytes();
    if (rotated.isNotEmpty) {
      _rotated.add(rotated);
      chunks.addAll(utf8.encode('===== diagnostic.log.1 =====\n'));
      chunks.addAll(rotated);
      if (chunks.last != 10) chunks.add(10);
    }
    final current = _current.takeBytes();
    if (current.isNotEmpty) {
      _current.add(current);
      chunks.addAll(utf8.encode('===== diagnostic.log =====\n'));
      chunks.addAll(current);
    }
    return DiagnosticLogSnapshot(
      bytes: chunks,
      lastModified: DateTime.now().toUtc(),
      directoryPath: directory.path,
    );
  }

  Future<void> flush() async {
    await _chain;
    await _sink?.flush();
  }

  Future<void> close() async {
    await _chain;
    final sink = _sink;
    _sink = null;
    _opened = false;
    // 不等待磁盘关闭：Widget 测试的 FakeAsync 无法推进 dart:io 回调。
    sink?.close();
  }

  Future<void> _write(String line) async {
    final redacted = redactor.redact(line);
    final payload = utf8.encode('$redacted\n');
    if (_bytes + payload.length > maxBytes) {
      await _rotate();
    }
    _current.add(payload);
    _sink?.add(payload);
    _bytes += payload.length;
  }

  Future<void> _rotate() async {
    await _sink?.flush();
    await _sink?.close();
    _sink = null;
    _rotated.clear();
    _rotated.add(_current.takeBytes());
    _current.clear();
    if (await rotatedFile.exists()) {
      await rotatedFile.delete();
    }
    if (await currentFile.exists()) {
      await currentFile.rename(rotatedFile.path);
    }
    _sink = currentFile.openWrite(mode: FileMode.write);
    _bytes = 0;
  }
}

/// 上报用的合并快照，空字节表示没有可上传内容。
final class DiagnosticLogSnapshot {
  const DiagnosticLogSnapshot({
    required this.bytes,
    required this.lastModified,
    required this.directoryPath,
  });

  final List<int> bytes;
  final DateTime? lastModified;
  final String directoryPath;

  bool get isEmpty => bytes.isEmpty;
  int get sizeBytes => bytes.length;
}
