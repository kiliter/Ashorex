import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 课程详情：课时按 `sortIndex` 呈现，进度与「以前看过」都由服务端给出，
/// 时长缺失的课时标记为不可度量，不能参与目标进度设定。
void main() {
  test('课时列表按服务端顺序解析，并带出已看进度', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-1',
        json: courseDetailJson(
          id: 'c-1',
          resources: [
            courseResourceJson(id: 'r-1', title: '第 01 讲 总论', sortIndex: 1),
            courseResourceJson(
              id: 'r-2',
              title: '第 02 讲 分论',
              sortIndex: 2,
              maxPositionMs: 900000,
              watchedMs: 900000,
              progressPermille: 500,
              completedBefore: true,
            ),
          ],
        ),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(courseDetailProvider('c-1').future);

    expect(detail.summary.id, 'c-1');
    expect(detail.resources.map((resource) => resource.title), [
      '第 01 讲 总论',
      '第 02 讲 分论',
    ]);
    expect(detail.resources.last.progressPermille, 500);
    expect(detail.resources.last.completedBefore, isTrue);
    expect(detail.resources.first.completedBefore, isFalse);
  });

  test('时长缺失的课时不可度量', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-1',
        json: courseDetailJson(
          id: 'c-1',
          resources: [
            courseResourceJson(id: 'r-1', durationMs: null, measurable: false),
          ],
        ),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(courseDetailProvider('c-1').future);

    expect(detail.resources.single.durationMs, isNull);
    expect(detail.resources.single.measurable, isFalse);
  });

  test('元数据只读展示，全部来自 Emby 投影', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-1',
        json: {
          'summary': courseSummaryJson(id: 'c-1', productionYear: 2025),
          'genres': ['法律', '公考'],
          'tags': ['重点'],
          'people': [
            {'name': '袁东', 'role': '讲师'},
          ],
          'resources': const [],
        },
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(courseDetailProvider('c-1').future);

    expect(detail.genres, ['法律', '公考']);
    expect(detail.tags, ['重点']);
    expect(detail.people, ['袁东']);
    expect(detail.summary.productionYear, 2025);
    expect(detail.resources, isEmpty);
  });

  test('课程不存在时抛出可识别的接口异常，不静默返回空详情', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-missing',
        status: 404,
        errorCode: 'COURSE_NOT_FOUND',
      );
    final repository = buildRepository(backend);

    ApiException? captured;
    try {
      await repository.loadCourse('c-missing');
    } on ApiException catch (exception) {
      captured = exception;
    }

    expect(captured, isNotNull);
    expect(captured!.statusCode, 404);
    expect(captured.errorCode, 'COURSE_NOT_FOUND');
  });

  test('资源类型缺失时按视频兜底，材料需显式声明', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses/c-1',
        json: {
          'summary': courseSummaryJson(id: 'c-1'),
          'genres': const [],
          'tags': const [],
          'people': const [],
          'resources': [
            {
              'id': 'r-1',
              'title': '无类型资源',
              'sortIndex': 1,
              'durationMs': 60000,
              'measurable': true,
            },
            {
              'id': 'r-2',
              'resourceType': 'DOCUMENT',
              'title': '讲义',
              'sortIndex': 2,
              'pageCount': 30,
              'measurable': true,
            },
          ],
        },
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final detail = await container.read(courseDetailProvider('c-1').future);

    expect(detail.resources.first.resourceType, ResourceType.video);
    expect(detail.resources.last.resourceType, ResourceType.document);
    expect(detail.resources.last.pageCount, 30);
  });
}

ProviderContainer _container(FakeBackend backend) {
  return ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
}
