import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 课程库筛选：流派 / 标签 / 人物 / 年份全部来自 Emby 元数据，客户端只做筛选组合，
/// 同一维度再次点选即取消，筛选条件必须原样出现在请求查询串里。
void main() {
  test('筛选维度全部来自服务端投影，附带课程数', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/facets',
        json: {
          'genres': [
            {'value': '法律', 'courseCount': 12},
          ],
          'tags': [
            {'value': '重点', 'courseCount': 5},
          ],
          'people': [
            {'value': '袁东', 'courseCount': 3},
          ],
          'years': [
            {'value': '2026', 'courseCount': 8},
          ],
        },
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final facets = await container.read(catalogFacetsProvider.future);

    expect(facets.genres.single.value, '法律');
    expect(facets.genres.single.courseCount, 12);
    expect(facets.people.single.value, '袁东');
    expect(facets.years.single.value, '2026');
  });

  test('同一维度再次点选即取消筛选', () {
    final container = _container(FakeBackend());
    addTearDown(container.dispose);
    final controller = container.read(catalogFilterProvider.notifier);

    controller.toggleGenre('法律');
    expect(container.read(catalogFilterProvider).genre, '法律');
    controller.toggleGenre('法律');
    expect(container.read(catalogFilterProvider).genre, isNull);

    controller.toggleTag('重点');
    controller.togglePerson('袁东');
    expect(container.read(catalogFilterProvider).tag, '重点');
    expect(container.read(catalogFilterProvider).person, '袁东');
    controller.toggleTag('重点');
    expect(container.read(catalogFilterProvider).tag, isNull);
    expect(container.read(catalogFilterProvider).person, '袁东');
  });

  test('筛选条件会进入课程列表请求的查询串', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [courseSummaryJson(id: 'c-1')],
      );
    final container = _container(backend);
    addTearDown(container.dispose);
    final controller = container.read(catalogFilterProvider.notifier);

    controller.toggleGenre('法律');
    controller.togglePerson('袁东');
    controller.setQuery('行政');
    await container.read(coursesProvider.future);

    final path = backend.lastRequest('GET', '/api/v1/catalog/courses').path;
    expect(path, contains('genre=%E6%B3%95%E5%BE%8B'));
    expect(path, contains('person=%E8%A2%81%E4%B8%9C'));
    expect(path, contains('q=%E8%A1%8C%E6%94%BF'));
  });

  test('无筛选时不拼查询串', () async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/catalog/courses', json: const []);
    final container = _container(backend);
    addTearDown(container.dispose);

    final courses = await container.read(coursesProvider.future);

    expect(courses, isEmpty);
    expect(
      backend.lastRequest('GET', '/api/v1/catalog/courses').path,
      '/api/v1/catalog/courses',
    );
  });

  test('课程卡无流派时回落到未分类，不显示空标签', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [courseSummaryJson(id: 'c-1', genres: const [])],
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final courses = await container.read(coursesProvider.future);

    expect(courses.single.primaryGenre, '未分类');
  });

  test('按人物分组只是本地展示开关，不改变请求', () async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/catalog/courses', json: const []);
    final container = _container(backend);
    addTearDown(container.dispose);

    container.read(catalogFilterProvider.notifier).setGroupByPerson(true);
    await container.read(coursesProvider.future);

    expect(container.read(catalogFilterProvider).groupByPerson, isTrue);
    expect(
      backend.lastRequest('GET', '/api/v1/catalog/courses').path,
      '/api/v1/catalog/courses',
    );
  });
}

ProviderContainer _container(FakeBackend backend) {
  return ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
}
