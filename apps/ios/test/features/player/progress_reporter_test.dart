import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';

import '../../support/fake_backend.dart';

/// 进度上报载荷：只上报 `positionMs` 与真实经过的 `deltaWatchedMs`，
/// 时间戳为 ISO-8601 UTC，前后台用 `appState` 区分；是否达标一律由服务端裁决。
void main() {
  test('上报载荷包含位置、增量、appState 与 UTC 时间戳', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/t-1/progress',
        json: {
          'status': 'IN_PROGRESS',
          'positionMs': 45000,
          'watchedMs': 45000,
          'progressPermille': 250,
          'completed': false,
          'targetProgressPermille': 300,
        },
      );
    final repository = buildRepository(backend);

    final result = await repository.reportProgress(
      't-1',
      clientSeq: 7,
      occurredAt: DateTime.utc(2026, 9, 7, 13, 20, 30),
      positionMs: 45000,
      deltaWatchedMs: 15000,
    );

    final body = backend.lastRequest('POST', '/api/v1/todos/t-1/progress').json;
    expect(body['clientSeq'], 7);
    expect(body['positionMs'], 45000);
    expect(body['deltaWatchedMs'], 15000);
    expect(body['eventType'], 'PROGRESS');
    expect(body['appState'], 'FOREGROUND');
    expect(body['occurredAt'], '2026-09-07T13:20:30.000Z');
    expect(result.status, TodoStatus.inProgress);
    expect(result.completed, isFalse);
    expect(result.progressPermille, 250);
  });

  test('切后台时 appState 为 BACKGROUND 且不累计观看时长', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/t-1/progress',
        json: {
          'status': 'IN_PROGRESS',
          'positionMs': 45000,
          'watchedMs': 45000,
          'progressPermille': 250,
          'completed': false,
        },
      );
    final repository = buildRepository(backend);

    await repository.reportProgress(
      't-1',
      clientSeq: 8,
      occurredAt: DateTime.utc(2026, 9, 7, 13, 21),
      eventType: 'BACKGROUND',
      positionMs: 45000,
      deltaWatchedMs: 0,
      foreground: false,
    );

    final body = backend.lastRequest('POST', '/api/v1/todos/t-1/progress').json;
    expect(body['appState'], 'BACKGROUND');
    expect(body['deltaWatchedMs'], 0);
    expect(body['eventType'], 'BACKGROUND');
  });

  test('本地时间会被转成 UTC 上报，避免时区污染统计口径', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/t-1/progress',
        json: {
          'status': 'IN_PROGRESS',
          'positionMs': 1000,
          'watchedMs': 1000,
          'progressPermille': 10,
          'completed': false,
        },
      );
    final repository = buildRepository(backend);
    final local = DateTime.utc(2026, 9, 7, 16, 0).toLocal();

    await repository.reportProgress(
      't-1',
      clientSeq: 0,
      occurredAt: local,
      positionMs: 1000,
      deltaWatchedMs: 1000,
    );

    expect(
      backend
          .lastRequest('POST', '/api/v1/todos/t-1/progress')
          .json['occurredAt'],
      '2026-09-07T16:00:00.000Z',
    );
  });

  test('达标由服务端裁决：客户端原样采用 completed 与 status', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/t-1/progress',
        json: {
          'status': 'DONE',
          'positionMs': 320000,
          'watchedMs': 320000,
          'progressPermille': 320,
          'completed': true,
          'targetProgressPermille': 300,
        },
      );
    final repository = buildRepository(backend);

    final result = await repository.reportProgress(
      't-1',
      clientSeq: 1,
      occurredAt: DateTime.utc(2026, 9, 7, 14),
      positionMs: 320000,
      deltaWatchedMs: 15000,
    );

    expect(result.completed, isTrue);
    expect(result.status, TodoStatus.done);
    expect(result.targetProgressPermille, 300);
  });
}
