import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 考试目标：日期一律按用户时区的 `YYYY-MM-DD` 提交，倒计时天数与紧急度分档
/// 由服务端裁决，客户端不自行计算。
void main() {
  test('创建目标时按 YYYY-MM-DD 提交考试日期', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/exam-goals', json: goalJson(id: 'g-1'));
    final repository = buildRepository(backend);

    final goal = await repository.createGoal(
      name: '法考客观题',
      examDate: DateTime(2026, 9, 20, 23, 59),
      note: '第一轮',
      primary: true,
    );

    final body = backend.lastRequest('POST', '/api/v1/exam-goals').json;
    expect(body['examDate'], '2026-09-20');
    expect(body['name'], '法考客观题');
    expect(body['note'], '第一轮');
    expect(body['primary'], isTrue);
    expect(goal.id, 'g-1');
  });

  test('部分更新只提交被修改的字段', () async {
    final backend = FakeBackend()
      ..on(
        'PATCH',
        '/api/v1/exam-goals/g-1',
        json: goalJson(id: 'g-1', name: '法考主观题'),
      );
    final repository = buildRepository(backend);

    await repository.updateGoal(goalId: 'g-1', name: '法考主观题');

    final body = backend.lastRequest('PATCH', '/api/v1/exam-goals/g-1').json;
    expect(body['name'], '法考主观题');
    expect(body.containsKey('examDate'), isFalse);
    expect(body.containsKey('primary'), isFalse);
  });

  test('倒计时天数与紧急度分档全部采用服务端结果', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/exam-goals',
        json: [
          goalJson(id: 'g-1', daysRemaining: 2, urgency: 'URGENT'),
          goalJson(
            id: 'g-2',
            name: '公考省考',
            daysRemaining: -3,
            urgency: 'EXPIRED',
            primary: false,
          ),
        ],
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final goals = await container.read(goalsProvider.future);

    expect(goals.first.urgency, GoalUrgency.urgent);
    expect(goals.first.daysRemaining, 2);
    expect(goals.last.urgency, GoalUrgency.expired);
    expect(goals.last.daysRemaining, -3);
    expect(goals.where((goal) => goal.primary).length, 1);
  });

  test('未知紧急度分档回落为普通，不让页面崩在枚举上', () async {
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/exam-goals',
        json: [goalJson(id: 'g-1', urgency: 'SOMETHING_NEW')],
      );
    final container = _container(backend);
    addTearDown(container.dispose);

    final goals = await container.read(goalsProvider.future);

    expect(goals.single.urgency, GoalUrgency.normal);
  });

  test('删除目标走 DELETE 且不携带请求体', () async {
    final backend = FakeBackend()..on('DELETE', '/api/v1/exam-goals/g-1');
    final repository = buildRepository(backend);

    await repository.deleteGoal('g-1');

    final request = backend.lastRequest('DELETE', '/api/v1/exam-goals/g-1');
    expect(request.body, isNull);
    expect(backend.callCount('DELETE', '/api/v1/exam-goals/g-1'), 1);
  });
}

ProviderContainer _container(FakeBackend backend) {
  return ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
    ],
  );
}
