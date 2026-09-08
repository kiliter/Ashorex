import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/profile/presentation/profile_page.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 我的页：催办策略与免打扰全部只读，App 端不得出现任何阈值或渠道开关；
/// 心跳卡按在线状态双编码（图标 + 文字），督学端入口只在有学员时出现。
void main() {
  testWidgets('滚动设置时用户信息固定，心跳状态随内容滚动', (tester) async {
    await _pump(tester, _backend());
    final header = find.textContaining(' · Asia/Shanghai');
    final before = tester.getTopLeft(header);
    final heartbeatBefore = tester.getTopLeft(find.text('在线 · 心跳正常'));
    await tester.drag(find.byType(ListView).first, const Offset(0, -250));
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(header), before);
    final heartbeat = find.text('在线 · 心跳正常');
    if (heartbeat.evaluate().isNotEmpty) {
      expect(tester.getTopLeft(heartbeat).dy, lessThan(heartbeatBefore.dy));
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('催办策略与免打扰只读展示，并说明配置只在服务端', (tester) async {
    await _pump(tester, _backend());

    expect(find.text('提醒与在线（只读）'), findsOneWidget);
    expect(find.text('60 秒'), findsOneWidget);
    expect(find.textContaining('无操作 90 分钟触发 · 每日最多 3 次'), findsOneWidget);
    expect(find.text('23:30 – 07:00'), findsOneWidget);
    expect(find.text('App 端不提供任何催办阈值或渠道开关，这些配置只在服务端管理后台维护。'), findsOneWidget);
    // 只读页面不得出现任何开关控件。
    expect(find.byType(Switch), findsNothing);
  });

  testWidgets('心跳卡默认按在线渲染，且同时用图标与文字表达状态', (tester) async {
    await _pump(tester, _backend());

    expect(find.text('在线 · 心跳正常'), findsOneWidget);
    expect(find.byIcon(Icons.monitor_heart_outlined), findsWidgets);
    expect(find.text('尚未上报'), findsOneWidget);
  });

  testWidgets('心跳失败时切换为离线文案与断网图标', (tester) async {
    final container = _container(_backend());
    container
        .read(heartbeatStatusProvider.notifier)
        .update(online: false, queuedEvents: 2);
    await _pumpWith(tester, container);

    expect(find.text('离线 · 心跳失败'), findsOneWidget);
    expect(find.byIcon(Icons.wifi_off), findsOneWidget);
  });

  testWidgets('没有督学人也没有学员时不渲染督学卡', (tester) async {
    await _pump(tester, _backend());

    expect(find.textContaining('我的督学人'), findsNothing);
    expect(find.text('切换到督学端'), findsNothing);
  });

  testWidgets('有督学人时展示绑定关系与同步说明', (tester) async {
    await _pump(
      tester,
      _backend(
        supervisors: [
          {
            'userId': 'u-9',
            'displayName': '王五',
            'kind': 'PRIMARY',
            'canNag': true,
          },
        ],
      ),
    );

    expect(find.text('我的督学人 · 王五'), findsOneWidget);
    expect(find.text('删除待办与催办回应会同步给督学人'), findsOneWidget);
    expect(find.text('已绑定'), findsOneWidget);
  });

  testWidgets('双身份账号在学员端也不提供身份切换', (tester) async {
    await _pump(tester, _backend(supervisorMode: true, learnerCount: 3));

    expect(find.text('切换到督学端'), findsNothing);
    expect(find.text('我督学 3 名学员'), findsNothing);
  });

  testWidgets('时区作为每日边界依据，明确标注影响统计口径', (tester) async {
    await _pump(tester, _backend());

    expect(find.text('时区'), findsOneWidget);
    expect(find.text('Asia/Shanghai'), findsOneWidget);
    expect(find.text('影响每日边界与统计口径'), findsOneWidget);
  });
}

FakeBackend _backend({
  List<Map<String, Object?>> supervisors = const [],
  bool supervisorMode = false,
  int learnerCount = 0,
}) {
  return FakeBackend()
    ..on('GET', '/api/v1/exam-goals', json: const [])
    ..on(
      'GET',
      '/api/v1/me',
      json: meJson(
        supervisors: supervisors,
        supervisorMode: supervisorMode,
        learnerCount: learnerCount,
      ),
    );
}

ProviderContainer _container(FakeBackend backend) {
  final container = ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      serverConfigurationControllerProvider.overrideWithValue(
        ServerConfigurationController(
          initialConfiguration: ServerConfiguration.parse(
            'https://shangan.test',
          ),
          store: _MemoryServerConfigurationStore(),
          tokenStore: MemoryTokenStore(),
        ),
      ),
    ],
  );
  addTearDown(container.dispose);
  return container;
}

Future<void> _pump(WidgetTester tester, FakeBackend backend) {
  return _pumpWith(tester, _container(backend));
}

Future<void> _pumpWith(WidgetTester tester, ProviderContainer container) async {
  tester.view.physicalSize = const Size(1170, 2532);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(home: Scaffold(body: ProfilePage())),
    ),
  );
  await tester.pumpAndSettle();
}

final class _MemoryServerConfigurationStore
    implements ServerConfigurationStore {
  @override
  Future<ServerConfiguration> load({required String defaultBaseUrl}) async =>
      ServerConfiguration.parse(defaultBaseUrl);

  @override
  Future<void> save(ServerConfiguration configuration) async {}
}
