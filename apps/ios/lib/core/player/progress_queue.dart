import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/data/shangan_repository.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

/// 只存尚未确认的播放事件；生产按服务器和用户隔离，测试可注入内存存储。
abstract interface class ProgressStore {
  Future<String?> read(String scope);
  Future<void> write(String scope, String value);
}

final class PreferencesProgressStore implements ProgressStore {
  final _preferences = SharedPreferencesAsync();
  String _key(String scope) => 'shangan.progress.${Uri.encodeComponent(scope)}';
  @override
  Future<String?> read(String scope) => _preferences.getString(_key(scope));
  @override
  Future<void> write(String scope, String value) =>
      _preferences.setString(_key(scope), value);
}

/// 队列独立于播放页存活；入队先落盘，收到成功响应才删除，崩溃重放沿用原序号。
final class ProgressOutbox {
  ProgressOutbox({
    required this.repository,
    this.store,
    String? Function()? currentScope,
  }) : _currentScope = currentScope ?? (() => 'memory');
  final ShanganRepository repository;
  final ProgressStore? store;
  final String? Function() _currentScope;
  final _events = <String, List<Map<String, dynamic>>>{};
  Future<void> _writes = Future<void>.value();
  Future<ProgressResult?>? _flushing;

  String? get scope => _currentScope();
  int get depth => _events[scope]?.length ?? 0;

  /// 将读取和写入排成一条链，防止心跳删队首时覆盖新入队事件。
  Future<T> _mutate<T>(Future<T> Function() action) {
    final result = _writes.then((_) => action());
    _writes = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  Future<List<Map<String, dynamic>>> _load(String owner) async {
    if (_events.containsKey(owner)) return _events[owner]!;
    final saved = await store?.read(owner);
    return _events[owner] = saved == null
        ? []
        : (jsonDecode(saved) as List)
              .map((e) => Map<String, dynamic>.from(e as Map))
              .toList();
  }

  Future<void> _save(String owner) async {
    await store?.write(owner, jsonEncode(_events[owner]));
  }

  Future<ProgressResult?> submit(
    String owner,
    Map<String, dynamic> event,
  ) async {
    await _mutate(() async {
      final events = await _load(owner);
      // 重进页面或系统时钟回拨时，仍晚于这个 Todo 尚未发送的序号。
      for (final previous in events) {
        if (previous['todoId'] == event['todoId'] &&
            (previous['clientSeq'] as int) >= (event['clientSeq'] as int)) {
          event['clientSeq'] = (previous['clientSeq'] as int) + 1;
        }
      }
      events.add(event);
      await _save(owner);
    });
    return flush(todoId: event['todoId'] as String);
  }

  /// 心跳成功后也重放，因此离开播放页、重启 App 后仍能恢复发送。
  Future<ProgressResult?> flush({String? todoId}) async {
    if (_flushing != null) {
      await _flushing;
      return flush(todoId: todoId);
    }
    final owner = scope;
    if (owner == null) return null;
    final operation = _drain(owner, todoId);
    _flushing = operation;
    try {
      return await operation;
    } finally {
      _flushing = null;
    }
  }

  Future<ProgressResult?> _drain(String owner, String? todoId) async {
    ProgressResult? last;
    while (scope == owner) {
      final event = await _mutate(() async {
        final events = await _load(owner);
        return events.isEmpty ? null : events.first;
      });
      if (event == null || scope != owner) break;
      try {
        final result = await repository.reportProgress(
          event['todoId'] as String,
          clientSeq: event['clientSeq'] as int,
          occurredAt: DateTime.parse(event['occurredAt'] as String),
          eventType: event['eventType'] as String,
          positionMs: event['positionMs'] as int,
          deltaWatchedMs: event['deltaWatchedMs'] as int,
          foreground: event['foreground'] as bool,
        );
        if (event['todoId'] == todoId) last = result;
      } on ApiException catch (error) {
        // 已删除的 Todo 无处回填；只丢弃服务端明确确认不存在的事件，
        // 其他失败保留原始载荷和幂等键，下一次心跳再试。
        if (error.errorCode != 'TODO_NOT_FOUND') break;
      } catch (_) {
        break;
      }
      await _mutate(() async {
        // 请求等待期间可能换账号；归属校验的 404 不能当作原账号已删 Todo。
        // 成功响应也保留到原账号重放，沿用幂等键不会重复累计。
        if (scope != owner) return;
        _events[owner]!.remove(event);
        await _save(owner);
      });
    }
    return last;
  }
}

final progressOutboxProvider = Provider<ProgressOutbox>(
  (ref) => ProgressOutbox(repository: ref.watch(shanganRepositoryProvider)),
);

/// 单个播放页只持有课时和序号，真正的待同步事件由应用级队列保存。
final class ProgressQueue {
  ProgressQueue({
    required this.repository,
    required this.todoId,
    int? initialSequence,
    ProgressOutbox? outbox,
  }) : _seq = initialSequence ?? DateTime.now().microsecondsSinceEpoch,
       _outbox = outbox ?? ProgressOutbox(repository: repository) {
    _owner = _outbox.scope;
  }
  final ShanganRepository repository;
  final String todoId;
  final ProgressOutbox _outbox;
  late final String? _owner;
  int _seq;
  int get depth => _outbox.depth;

  Future<ProgressResult?> submit({
    required int positionMs,
    required int deltaWatchedMs,
    required bool foreground,
    String eventType = 'PROGRESS',
  }) async {
    final owner = _owner;
    if (owner == null) return null;
    return _outbox.submit(owner, {
      'todoId': todoId,
      'clientSeq': _seq++,
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
      'positionMs': positionMs,
      'deltaWatchedMs': deltaWatchedMs,
      'foreground': foreground,
      'eventType': eventType,
    });
  }

  Future<ProgressResult?> flush() => _outbox.flush(todoId: todoId);
}
