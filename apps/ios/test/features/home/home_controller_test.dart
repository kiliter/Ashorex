import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 首页选择控制器：视图档位与 `localDate` 是「今日 / 历史某天」的唯一依据，
/// 因此今日由服务端按账号时区确定，只有显式选择的日期才传 `date` 参数。
void main() {
  test('默认今日采用服务端账号日期，请求不携带设备日期', () async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/todos', json: dayViewJson(date: '2030-01-02'));
    final container = _container(backend);
    addTearDown(container.dispose);
    container.read(homeSelectionProvider);
    await container.read(serverTodayProvider.future);
    final view = await container.read(dayViewProvider.future);
    expect(container.read(homeSelectionProvider).date, DateTime(2030, 1, 2));
    expect(view.date, DateTime(2030, 1, 2));
    expect(
      backend.requests.every((request) => !request.path.contains('date=')),
      isTrue,
    );
  });

  test('选择历史日期会归一化到零点并切回日视图', () {
    final container = _container(FakeBackend());
    addTearDown(container.dispose);
    final controller = container.read(homeSelectionProvider.notifier);

    controller.selectRange(HomeRange.month);
    controller.selectDate(DateTime(2026, 9, 3, 21, 45, 30));

    final selection = container.read(homeSelectionProvider);
    expect(selection.range, HomeRange.day);
    expect(selection.date, DateTime(2026, 9, 3));
  });

  test('月视图里点日历格只换日期，仍留在月视图', () {
    final container = _container(FakeBackend());
    addTearDown(container.dispose);
    final controller = container.read(homeSelectionProvider.notifier);

    controller.selectRange(HomeRange.month);
    controller.selectDateKeepingRange(DateTime(2026, 9, 11, 8, 0));

    final selection = container.read(homeSelectionProvider);
    expect(selection.range, HomeRange.month);
    expect(selection.date, DateTime(2026, 9, 11));
  });

  test('回到今天刷新服务端日期，不使用设备时区', () async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/todos', json: dayViewJson(date: '2030-01-02'));
    final container = _container(backend);
    addTearDown(container.dispose);
    final controller = container.read(homeSelectionProvider.notifier);
    await container.read(serverTodayProvider.future);
    controller.selectRange(HomeRange.week);
    controller.selectDateKeepingRange(DateTime(2026, 1, 1));
    controller.goToday();
    await container.read(serverTodayProvider.future);
    expect(container.read(homeSelectionProvider).range, HomeRange.day);
    expect(container.read(homeSelectionProvider).date, DateTime(2030, 1, 2));
  });

  test('日视图按选中日期请求服务端，并解析出三段分组', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/todos',
        json: dayViewJson(
          date: '2026-09-03',
          today: false,
          history: true,
          todos: [
            todoJson(id: 't-1', status: 'TODO'),
            todoJson(id: 't-2', status: 'IN_PROGRESS'),
            todoJson(id: 't-3', status: 'DONE'),
          ],
        ),
      );
    final container = _container(backend);
    addTearDown(container.dispose);
    container
        .read(homeSelectionProvider.notifier)
        .selectDate(DateTime(2026, 9, 3));

    final view = await container.read(dayViewProvider.future);

    expect(
      backend.lastRequest('GET', '/api/v1/todos').path,
      contains('view=DAY'),
    );
    expect(
      backend.lastRequest('GET', '/api/v1/todos').path,
      contains('date=2026-09-03'),
    );
    expect(view.history, isTrue);
    expect(view.notStarted.map((todo) => todo.id), ['t-1']);
    expect(view.inProgress.map((todo) => todo.id), ['t-2']);
    expect(view.completed.map((todo) => todo.id), ['t-3']);
    expect(view.totals.pending, 2);
  });
}

ProviderContainer _container(FakeBackend backend) {
  return ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
}
