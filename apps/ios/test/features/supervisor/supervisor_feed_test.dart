import 'package:flutter/material.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/supervisor/presentation/supervisor_shell.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 提醒页必须区分本人、管理员和其他督学，不能把所有手动催办都算作「我发的」。
void main() {
  test('旧接口缺少或返回空的本人标志时按非本人解析', () {
    final json = <String, dynamic>{
      'kind': 'NAG',
      'learnerUserId': 'u',
      'occurredAt': '2026-09-08T01:00:00Z',
      'title': '催办',
      'tag': 'SUPERVISOR',
      'status': 'PENDING',
    };
    expect(SupervisorFeedItem.fromJson(json).sentByMe, isFalse);
    expect(
      SupervisorFeedItem.fromJson({...json, 'sentByMe': null}).sentByMe,
      isFalse,
    );
  });
  testWidgets('我发的筛选只保留服务端确认的本人记录', (tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    Map<String, Object?> item(String title, String tag, bool mine) => {
      'kind': 'NAG',
      'learnerUserId': 'u-2',
      'occurredAt': '2026-09-08T01:00:00Z',
      'title': title,
      'tag': tag,
      'status': 'DELIVERED',
      'reasonText': '',
      'sentByMe': mine,
    };
    final backend = FakeBackend()
      ..on('GET', '/api/v1/me', json: meJson())
      ..on(
        'GET',
        '/api/v1/supervisor/learners',
        json: [learnerOverviewJson(userId: 'u-2')],
      )
      ..on(
        'GET',
        '/api/v1/supervisor/feed',
        json: [
          item('本人发起', 'SUPERVISOR', true),
          item('他人发起', 'SUPERVISOR', false),
          item('后台发起', 'MANUAL', false),
        ],
      );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: const MaterialApp(home: SupervisorShell()),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('提醒').last);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ShanganFilterChip, '我发的'));
    await tester.pumpAndSettle();
    expect(find.textContaining('本人发起'), findsOneWidget);
    expect(find.textContaining('他人发起'), findsNothing);
    expect(find.textContaining('后台发起'), findsNothing);
  });
}
