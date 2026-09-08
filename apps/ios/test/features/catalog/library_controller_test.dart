import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 课程库筛选：流派 / 标签 / 人物 / 年份全部来自 Emby 元数据，客户端只做筛选组合，
/// 同类并集跨类交集，断言实际课程结果而非仅检查查询参数。
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

  test('多选同类并集跨类交集，应用和清空均改变真实结果', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/catalog/courses',
        json: [
          courseSummaryJson(
            id: 'a',
            title: '行政法',
            genres: ['法律'],
            people: ['甲'],
            tags: ['重点'],
          ),
          courseSummaryJson(
            id: 'b',
            title: '数学',
            genres: ['数学'],
            people: ['乙'],
            tags: ['重点'],
          ),
          courseSummaryJson(
            id: 'c',
            title: '英语',
            genres: ['英语'],
            people: ['甲'],
            tags: ['重点'],
          ),
          courseSummaryJson(
            id: 'd',
            title: '民法',
            genres: ['法律'],
            people: ['丙'],
            tags: ['重点'],
          ),
          courseSummaryJson(
            id: 'e',
            title: '基础法',
            genres: ['法律'],
            people: ['甲'],
            tags: ['基础'],
          ),
        ],
      );
    final container = _container(backend);
    addTearDown(container.dispose);
    final controller = container.read(catalogFilterProvider.notifier);
    final subscription = container.listen(coursesProvider, (_, _) {});
    addTearDown(subscription.close);
    expect((await container.read(coursesProvider.future)).map((c) => c.id), [
      'a',
      'b',
      'c',
      'd',
      'e',
    ]);
    controller.apply(
      const CatalogFilter(
        genres: {'法律', '数学'},
        people: {'甲', '乙'},
        tags: {'重点'},
      ),
    );
    expect((await container.read(coursesProvider.future)).map((c) => c.id), [
      'a',
      'b',
    ]);
    controller.setQuery('行政');
    expect((await container.read(coursesProvider.future)).map((c) => c.id), [
      'a',
    ]);
    controller.setQuery('不存在');
    expect(await container.read(coursesProvider.future), isEmpty);
    controller.apply(const CatalogFilter());
    expect((await container.read(coursesProvider.future)).map((c) => c.id), [
      'a',
      'b',
      'c',
      'd',
      'e',
    ]);
    expect(backend.callCount('GET', '/api/v1/catalog/courses'), 1);
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
