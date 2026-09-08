import 'package:flutter_test/flutter_test.dart';
import '../../support/fake_backend.dart';

/// 使用真实仓库与假 HTTP 传输，检查预览/确认协议，不模拟被测仓库。
void main() {
  test('预览不写入，确认只提交用户选中的原待办 ID', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/course-additions/preview',
        json: {
          'items': [
            {
              'resourceId': 'resource',
              'title': '第一讲',
              'status': 'HISTORY',
              'history': [
                {
                  'id': 'old',
                  'localDate': '2026-09-07',
                  'title': '第一讲',
                  'targetProgressPermille': 500,
                  'progressPositionMs': 60000,
                  'watchedMs': 30000,
                },
              ],
            },
          ],
        },
      )
      ..on(
        'POST',
        '/api/v1/todos/course-additions',
        json: {
          'created': 0,
          'deferred': 1,
          'reused': 0,
          'skipped': 0,
          'items': [],
        },
      );
    final repository = buildRepository(backend);
    final items = [
      repository.courseTodoPayload(
        resourceId: 'resource',
        targetProgressPermille: 1000,
        localDate: DateTime(2026, 9, 8),
      ),
    ];
    final preview = await repository.previewCourseAdditions(items);
    expect(preview.single.history.single.progressPositionMs, 60000);
    expect(backend.requests.length, 1);
    final result = await repository.addCourses(items, ['old']);
    expect(
      backend
          .lastRequest('POST', '/api/v1/todos/course-additions')
          .json['reuseTodoIds'],
      ['old'],
    );
    expect(result.deferred, 1);
    expect(result.message, '新增 0 个课时，顺延 1 个，复用 0 个，跳过 0 个');
  });
}
