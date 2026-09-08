import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 督学端学员详情：必须能看到当日 Todo、删除原因台账与统计口径；
/// 删除原因是督学的核心依据，缺一项都不行。
void main() {
  test('学员详情同时给出在线状态、当日 Todo、删除台账与统计', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/supervisor/learners/u-2',
        json: {
          'userId': 'u-2',
          'username': 'lisi',
          'displayName': '李四',
          'presence': {
            'state': 'IDLE',
            'idleMinutes': 95,
            'activity': {
              'pageLabel': '视频页',
              'stateLabel': '已暂停',
              'todoTitle': '行政法第 3 讲',
              'updatedAt': '2026-09-08T10:00:00Z',
              'background': false,
              'stale': false,
            },
          },
          'day': dayViewJson(
            todos: [
              todoJson(id: 't-1', status: 'DONE'),
              todoJson(id: 't-2', status: 'TODO', title: '未完成课时'),
            ],
            deletionCount: 1,
          ),
          'deletions': [
            {
              'titleSnapshot': '行政法第 3 讲',
              'reasonTag': 'GAVE_UP',
              'reasonText': '这周状态很差先放弃',
              'deletedAt': '2026-09-07T13:00:00Z',
            },
          ],
          'stats': statsJson(totalTodos: 2, doneTodos: 1, watchedMs: 1800000),
        },
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(learnerDetailProvider('u-2').future);

    expect(detail.presenceState, PresenceState.idle);
    expect(detail.idleMinutes, 95);
    expect(detail.activity.summary, '视频页 · 已暂停 · 行政法第 3 讲');
    expect(detail.activity.updatedAt, DateTime.utc(2026, 9, 8, 10));
    expect(detail.day.todos.length, 2);
    expect(detail.day.completed.single.id, 't-1');
    expect(detail.day.deletionCount, 1);
    expect(detail.deletions.single.reasonTag, 'GAVE_UP');
    expect(detail.deletions.single.reasonText, '这周状态很差先放弃');
    expect(detail.deletions.single.deletedAt.toUtc().hour, 13);
    expect(detail.stats.doneTodos, 1);
  });

  test('时间线区分删除与催办两类条目', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/supervisor/feed',
        json: [
          {
            'kind': 'DELETION',
            'learnerUserId': 'u-2',
            'occurredAt': '2026-09-07T13:00:00Z',
            'title': '行政法第 3 讲',
            'tag': 'GAVE_UP',
            'status': '',
            'reasonText': '不想学了',
          },
          {
            'kind': 'NAG',
            'learnerUserId': 'u-2',
            'occurredAt': '2026-09-07T14:00:00Z',
            'title': '自动催办',
            'tag': 'TEMP_BUSY',
            'status': 'RESPONDED',
            'reasonText': '临时加班',
          },
        ],
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final feed = await container.read(supervisorFeedProvider.future);

    expect(feed.first.isDeletion, isTrue);
    expect(feed.last.isDeletion, isFalse);
    expect(feed.last.status, 'RESPONDED');
  });

  test('一键督学把渠道与是否必须回应一起发给服务端', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/supervisor/learners/u-2/nag');
    final repository = buildRepository(backend);

    await repository.nagLearner('u-2', message: '别摆了，开始看第 3 讲');

    final body = backend
        .lastRequest('POST', '/api/v1/supervisor/learners/u-2/nag')
        .json;
    expect(body['message'], '别摆了，开始看第 3 讲');
    expect(body['channel'], 'AUTO');
    expect(body['requireReason'], isTrue);
  });

  test('不填文案时不下发空 message 字段', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/supervisor/learners/u-2/nag');
    final repository = buildRepository(backend);

    await repository.nagLearner('u-2');

    final body = backend
        .lastRequest('POST', '/api/v1/supervisor/learners/u-2/nag')
        .json;
    expect(body.containsKey('message'), isFalse);
  });

  test('报告区间会进入请求，并解析催办回应率所需的两个计数', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/supervisor/report',
        json: [
          {
            'userId': 'u-2',
            'username': 'lisi',
            'displayName': '李四',
            'stats': statsJson(range: 'MONTH'),
            'nagCount': 4,
            'nagRespondedCount': 3,
          },
        ],
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    container
        .read(supervisorReportRangeProvider.notifier)
        .select(HomeRange.month);
    final reports = await container.read(supervisorReportProvider.future);

    expect(
      backend.lastRequest('GET', '/api/v1/supervisor/report').path,
      contains('range=MONTH'),
    );
    expect(reports.single.nagCount, 4);
    expect(reports.single.nagRespondedCount, 3);
  });
}

ProviderContainer _container(FakeBackend backend) {
  return ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
}
