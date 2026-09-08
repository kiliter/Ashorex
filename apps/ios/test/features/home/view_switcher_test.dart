import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 日 / 周 / 月切换：周视图必须以选中日期所在周的周一为起点，
/// 月视图必须按 `YYYY-MM` 请求，切换本身不改变已选日期。
void main() {
  test('周视图以选中日期所在周的周一为起点', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/todos',
        json: rangeViewJson(
          start: '2026-09-07',
          end: '2026-09-13',
          days: [
            daySummaryJson(
              date: '2026-09-07',
              total: 3,
              done: 3,
              outcome: 'ALL_DONE',
            ),
            daySummaryJson(
              date: '2026-09-08',
              total: 2,
              done: 1,
              outcome: 'PARTIAL',
            ),
          ],
        ),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    // 2026-09-10 是周四，所在周的周一是 09-07。
    container
        .read(homeSelectionProvider.notifier)
        .selectDate(DateTime(2026, 9, 10));
    container.read(homeSelectionProvider.notifier).selectRange(HomeRange.week);

    final view = await container.read(weekViewProvider.future);

    final path = backend.lastRequest('GET', '/api/v1/todos').path;
    expect(path, contains('view=WEEK'));
    expect(path, contains('weekStart=2026-09-07'));
    expect(view.days.first.outcome, DayOutcome.allDone);
    expect(view.days.last.outcome, DayOutcome.partial);
    expect(view.totals.total, 5);
  });

  test('月视图按 YYYY-MM 请求', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/todos',
        json: rangeViewJson(start: '2026-09-01', end: '2026-09-30'),
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    container
        .read(homeSelectionProvider.notifier)
        .selectDate(DateTime(2026, 9, 10));
    container.read(homeSelectionProvider.notifier).selectRange(HomeRange.month);
    await container.read(monthViewProvider.future);

    final path = backend.lastRequest('GET', '/api/v1/todos').path;
    expect(path, contains('view=MONTH'));
    expect(path, contains('month=2026-09'));
  });

  test('切换视图不改变已选日期', () {
    final container = _container(FakeBackend());
    addTearDown(container.dispose);
    final controller = container.read(homeSelectionProvider.notifier);

    controller.selectDate(DateTime(2026, 9, 3));
    controller.selectRange(HomeRange.week);
    expect(container.read(homeSelectionProvider).date, DateTime(2026, 9, 3));

    controller.selectRange(HomeRange.month);
    expect(container.read(homeSelectionProvider).date, DateTime(2026, 9, 3));
    expect(container.read(homeSelectionProvider).range, HomeRange.month);
  });

  test('未完成汇总按逾期天数分出高危组', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/todos/pending-summary',
        json: {
          'total': 2,
          'countByType': {'COURSE': 1, 'TASK': 1},
          'items': [
            {
              'todo': todoJson(id: 't-1', localDate: '2026-09-06'),
              'overdueDays': 1,
              'bucket': 'ONE_DAY',
            },
            {
              'todo': todoJson(
                id: 't-2',
                localDate: '2026-09-01',
                todoType: 'TASK',
              ),
              'overdueDays': 6,
              'bucket': 'THREE_DAYS_OR_MORE',
            },
          ],
        },
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final summary = await container.read(pendingSummaryProvider.future);

    expect(summary.total, 2);
    expect(summary.countByType[TodoType.course], 1);
    expect(summary.items.first.severe, isFalse);
    expect(summary.items.last.severe, isTrue);
    expect(summary.items.last.overdueDays, 6);
  });
}

ProviderContainer _container(FakeBackend backend) {
  return ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
}
