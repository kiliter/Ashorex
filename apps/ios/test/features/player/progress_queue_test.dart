import 'dart:async';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/player/progress_queue.dart';

import '../../support/fake_backend.dart';

/// 进度上报队列：`clientSeq` 必须从 0 起严格递增，断网时事件留在队列里，
/// 恢复后按序重放且不跳号，重复由服务端幂等键拦截（见 Spec 7.1）。
void main() {
  test('复习轮次写入实际请求，离线重放保留入队时的轮次', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/todos/t-1/progress', status: 503);
    final repository = buildRepository(backend);
    final store = _MemoryProgressStore();
    final queue = ProgressQueue(
      repository: repository,
      todoId: 't-1',
      outbox: ProgressOutbox(repository: repository, store: store),
      initialSequence: 42,
    )..playbackEpoch = 2;
    await queue.submit(
      positionMs: 15000,
      deltaWatchedMs: 1000,
      foreground: true,
    );
    // 切换到新轮次不能改写已持久化事件的归属，且最终 HTTP 请求必须携带轮次。
    queue.playbackEpoch = 3;
    backend.on(
      'POST',
      '/api/v1/todos/t-1/progress',
      json: _progressJson(positionMs: 15000),
    );
    final restored = ProgressOutbox(repository: repository, store: store);
    await restored.flush();
    expect(backend.requests.map((r) => r.json['playbackEpoch']), [2, 2]);
    expect(backend.requests.last.json['positionMs'], 15000);
    expect(restored.depth, 0);
  });

  test('离线退出并重建队列后，按原幂等键补发且清除持久记录', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/todos/t-1/progress', status: 503);
    final repository = buildRepository(backend);
    final store = _MemoryProgressStore();
    final first = ProgressOutbox(repository: repository, store: store);
    await ProgressQueue(
      repository: repository,
      todoId: 't-1',
      outbox: first,
      initialSequence: 42,
    ).submit(positionMs: 15000, deltaWatchedMs: 15000, foreground: true);
    expect(first.depth, 1);
    backend.on(
      'POST',
      '/api/v1/todos/t-1/progress',
      json: _progressJson(positionMs: 15000),
    );
    final restored = ProgressOutbox(repository: repository, store: store);
    await restored.flush();
    expect(restored.depth, 0);
    expect(backend.requests.map((r) => r.json['clientSeq']), [42, 42]);
    expect(backend.requests.last.json['deltaWatchedMs'], 15000);
    expect(store.values['memory'], '[]');
  });

  test('退出登录及切换服务器账号不会发送原账号队列', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/todos/t-1/progress', status: 503);
    String? owner = 'server-a|user-a';
    final repository = buildRepository(backend);
    final outbox = ProgressOutbox(
      repository: repository,
      store: _MemoryProgressStore(),
      currentScope: () => owner,
    );
    final queue = ProgressQueue(
      repository: repository,
      todoId: 't-1',
      outbox: outbox,
    );
    await queue.submit(
      positionMs: 1000,
      deltaWatchedMs: 1000,
      foreground: true,
    );
    owner = null;
    await outbox.flush();
    owner = 'server-b|user-b';
    // 旧播放页注销时的最后一条仍只写入原账号，不串给新账号。
    await queue.submit(
      positionMs: 2000,
      deltaWatchedMs: 1000,
      foreground: true,
    );
    expect(backend.requests.length, 1);
    owner = 'server-a|user-a';
    backend.on(
      'POST',
      '/api/v1/todos/t-1/progress',
      json: _progressJson(positionMs: 2000),
    );
    await outbox.flush();
    expect(backend.requests.length, 3);
    expect(outbox.depth, 0);
  });

  test('已删除课时不阻塞后续待同步事件', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/todos/t-1/progress', status: 503)
      ..on(
        'POST',
        '/api/v1/todos/t-2/progress',
        json: _progressJson(positionMs: 2000),
      );
    final repository = buildRepository(backend);
    final outbox = ProgressOutbox(repository: repository);
    await ProgressQueue(
      repository: repository,
      todoId: 't-1',
      outbox: outbox,
    ).submit(positionMs: 1000, deltaWatchedMs: 1000, foreground: true);
    await ProgressQueue(
      repository: repository,
      todoId: 't-2',
      outbox: outbox,
    ).submit(positionMs: 2000, deltaWatchedMs: 1000, foreground: true);
    expect(outbox.depth, 2);
    backend.on(
      'POST',
      '/api/v1/todos/t-1/progress',
      status: 404,
      errorCode: 'TODO_NOT_FOUND',
    );
    await outbox.flush();
    expect(outbox.depth, 0);
    expect(
      backend
          .lastRequest('POST', '/api/v1/todos/t-2/progress')
          .json['deltaWatchedMs'],
      1000,
    );
  });

  test('请求等待期间换账号，迟到的归属404不丢弃原账号事件', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/t-1/progress',
        status: 404,
        errorCode: 'TODO_NOT_FOUND',
      );
    final delayed = _DelayedBackend(backend);
    final repository = buildRepository(delayed);
    String? owner = 'server|a';
    final outbox = ProgressOutbox(
      repository: repository,
      currentScope: () => owner,
    );
    final pending = ProgressQueue(
      repository: repository,
      todoId: 't-1',
      outbox: outbox,
      initialSequence: 7,
    ).submit(positionMs: 1000, deltaWatchedMs: 1000, foreground: true);
    await delayed.started.future;
    owner = 'server|b';
    delayed.release.complete();
    await pending;
    owner = 'server|a';
    expect(outbox.depth, 1);
    backend.on(
      'POST',
      '/api/v1/todos/t-1/progress',
      json: _progressJson(positionMs: 1000),
    );
    await outbox.flush();
    expect(outbox.depth, 0);
    expect(backend.requests.map((r) => r.json['clientSeq']), [7, 7]);
  });

  test('clientSeq 从 0 起递增，并按序发给服务端', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/t-1/progress',
        json: _progressJson(positionMs: 15000),
      );
    final queue = ProgressQueue(
      initialSequence: 0,
      repository: buildRepository(backend),
      todoId: 't-1',
    );

    await queue.submit(
      positionMs: 15000,
      deltaWatchedMs: 15000,
      foreground: true,
    );
    await queue.submit(
      positionMs: 30000,
      deltaWatchedMs: 15000,
      foreground: true,
    );

    final seqs = backend.requests
        .where((request) => request.path.endsWith('/progress'))
        .map((request) => request.json['clientSeq'])
        .toList();
    expect(seqs, [0, 1]);
    expect(queue.depth, 0);
  });

  test('上报失败时事件留在队列里，不丢事件也不跳号', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/todos/t-1/progress', status: 503);
    final queue = ProgressQueue(
      initialSequence: 0,
      repository: buildRepository(backend),
      todoId: 't-1',
    );

    final result = await queue.submit(
      positionMs: 15000,
      deltaWatchedMs: 15000,
      foreground: true,
    );

    expect(result, isNull);
    expect(queue.depth, 1);
  });

  test('恢复联网后按原始顺序重放全部积压事件', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/todos/t-1/progress', status: 503);
    final queue = ProgressQueue(
      initialSequence: 0,
      repository: buildRepository(backend),
      todoId: 't-1',
    );

    await queue.submit(
      positionMs: 15000,
      deltaWatchedMs: 15000,
      foreground: true,
    );
    await queue.submit(
      positionMs: 30000,
      deltaWatchedMs: 15000,
      foreground: true,
    );
    await queue.submit(
      positionMs: 30000,
      deltaWatchedMs: 0,
      foreground: false,
      eventType: 'BACKGROUND',
    );
    expect(queue.depth, 3);

    // 联网恢复：同一队列一次性重放。
    backend.on(
      'POST',
      '/api/v1/todos/t-1/progress',
      json: _progressJson(positionMs: 30000, progressPermille: 300),
    );
    final result = await queue.flush();

    expect(queue.depth, 0);
    expect(result, isNotNull);
    expect(result!.progressPermille, 300);
    final replayed = backend.requests
        .where(
          (request) =>
              request.path.endsWith('/progress') &&
              request.json['clientSeq'] != null,
        )
        .toList();
    // 每次 submit 都会尝试从队首重发，因此队首 0 被试了 4 次（3 次失败 + 1 次成功）；
    // 重复由服务端的 `(todoId, clientSeq)` 幂等键拦截，不会重复计时。
    expect(replayed.map((request) => request.json['clientSeq']), [
      0,
      0,
      0,
      0,
      1,
      2,
    ]);
    // 成功重放的尾部必须严格按 0 → 1 → 2 的原始顺序，不跳号、不乱序。
    expect(
      replayed.sublist(replayed.length - 3).map((r) => r.json['clientSeq']),
      [0, 1, 2],
    );
    expect(replayed.last.json['eventType'], 'BACKGROUND');
    expect(replayed.last.json['appState'], 'BACKGROUND');
  });

  test('部分失败时保留剩余事件，已成功的不再重发', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/t-1/progress',
        json: _progressJson(positionMs: 15000),
      );
    final queue = ProgressQueue(
      initialSequence: 0,
      repository: buildRepository(backend),
      todoId: 't-1',
    );

    await queue.submit(
      positionMs: 15000,
      deltaWatchedMs: 15000,
      foreground: true,
    );
    expect(queue.depth, 0);

    // 第二条上报时服务端不可用。
    backend.on('POST', '/api/v1/todos/t-1/progress', status: 503);
    await queue.submit(
      positionMs: 30000,
      deltaWatchedMs: 15000,
      foreground: true,
    );
    expect(queue.depth, 1);

    backend.on(
      'POST',
      '/api/v1/todos/t-1/progress',
      json: _progressJson(positionMs: 30000),
    );
    await queue.flush();
    expect(queue.depth, 0);
    // clientSeq 0 只发过一次，没有被重复重放。
    expect(
      backend.requests
          .where((request) => request.json['clientSeq'] == 0)
          .length,
      1,
    );
  });
}

Map<String, Object?> _progressJson({
  required int positionMs,
  int progressPermille = 150,
  bool completed = false,
  String status = 'IN_PROGRESS',
}) {
  return {
    'status': status,
    'positionMs': positionMs,
    'watchedMs': positionMs,
    'progressPermille': progressPermille,
    'completed': completed,
    'targetProgressPermille': 300,
  };
}

/// 模拟重新启动后仍可读到同一份本地事件，不使用真实磁盘或网络。
class _MemoryProgressStore implements ProgressStore {
  final values = <String, String>{};
  @override
  Future<String?> read(String scope) async => values[scope];
  @override
  Future<void> write(String scope, String value) async {
    values[scope] = value;
  }
}

/// 用显式完成信号控制 HTTP 响应时刻，复现请求中途切换登录身份。
class _DelayedBackend implements HttpClientAdapter {
  _DelayedBackend(this.backend);
  final FakeBackend backend;
  final started = Completer<void>();
  final release = Completer<void>();
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<void>? cancel,
  ) async {
    if (!started.isCompleted) started.complete();
    await release.future;
    return backend.fetch(options, stream, cancel);
  }

  @override
  void close({bool force = false}) => backend.close(force: force);
}
