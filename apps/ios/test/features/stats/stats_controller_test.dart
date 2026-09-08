import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 数据 Tab：区间与锚定日期都会进入请求，专注完成率与总时长口径由模型统一计算，
/// 倍速不改变时长口径，客户端不自行推算完成数。
void main() {
  test('默认按日请求，切换区间后按新区间请求', () async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/stats', json: statsJson());
    final container = _container(backend);
    addTearDown(container.dispose);

    await container.read(statsProvider.future);
    expect(
      backend.lastRequest('GET', '/api/v1/stats').path,
      isNot(contains('date=')),
    );
    expect(
      backend.lastRequest('GET', '/api/v1/stats').path,
      contains('range=DAY'),
    );

    container.read(statsRangeProvider.notifier).select(HomeRange.month);
    await container.read(statsProvider.future);
    expect(
      backend.lastRequest('GET', '/api/v1/stats').path,
      contains('range=MONTH'),
    );
  });

  test('锚定日期会进入请求并归一化到零点', () async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/stats', json: statsJson());
    final container = _container(backend);
    addTearDown(container.dispose);

    container
        .read(statsDateProvider.notifier)
        .select(DateTime(2026, 9, 3, 22, 15));
    await container.read(statsProvider.future);

    expect(container.read(statsDateProvider), DateTime(2026, 9, 3));
    expect(
      backend.lastRequest('GET', '/api/v1/stats').path,
      contains('date=2026-09-03'),
    );
  });

  test('总时长是观看与专注之和，专注完成率按两种终态计算', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/stats',
        json: statsJson(
          watchedMs: 3600000,
          focusedMs: 1800000,
          focusFinished: 3,
          focusAbandoned: 1,
        ),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final stats = await container.read(statsProvider.future);

    expect(stats.totalMs, 5400000);
    expect(stats.focusCompletionPercent, 75);
  });

  test('没有专注记录时完成率为 0 而不是除零', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/stats',
        json: statsJson(focusFinished: 0, focusAbandoned: 0),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final stats = await container.read(statsProvider.future);

    expect(stats.focusCompletionPercent, 0);
  });

  test('排行、备注标签与删除原因按服务端聚合结果解析', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/stats',
        json: statsJson(
          rankings: {
            'courses': [
              {'name': '行政法专题', 'watchedMs': 3600000, 'lessonCount': 4},
            ],
            'people': [
              {'name': '袁东', 'watchedMs': 1800000, 'lessonCount': 2},
            ],
            'genres': [
              {'name': '法律', 'watchedMs': 5400000, 'lessonCount': 6},
            ],
          },
          noteTags: [
            {'tag': 'NEED_REVIEW', 'count': 4},
            {'tag': 'UNKNOWN_TAG', 'count': 9},
          ],
          deletions: [
            {'reasonTag': 'GAVE_UP', 'count': 2},
          ],
        ),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final stats = await container.read(statsProvider.future);

    expect(stats.courseRanking.single.name, '行政法专题');
    expect(stats.personRanking.single.lessonCount, 2);
    expect(stats.genreRanking.single.watchedMs, 5400000);
    // 未知标签被安全丢弃，不会污染统计维度。
    expect(stats.noteTagCounts, {NoteTag.needReview: 4});
    expect(stats.deletionCounts, {'GAVE_UP': 2});
  });
}

ProviderContainer _container(FakeBackend backend) {
  return ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
}
