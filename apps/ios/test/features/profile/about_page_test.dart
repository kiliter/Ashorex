import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/profile/presentation/about_page.dart';

import '../../support/fake_backend.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('com.shangan/app-update'),
          (call) async {
            if (call.method == 'info') {
              return {'version': '2.6.0', 'platform': 'ios'};
            }
            return null;
          },
        );
  });

  tearDown(() async {
    await DiagnosticLog.resetForTest();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('com.shangan/app-update'),
          null,
        );
  });

  testWidgets('没有日志时提示且不能上报', (tester) async {
    await _pump(tester, FakeBackend());
    expect(find.textContaining('暂无诊断日志'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
  });
}

Future<void> _pump(WidgetTester tester, FakeBackend backend) async {
  final container = ProviderContainer(
    overrides: [
      shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      serverConfigurationControllerProvider.overrideWithValue(
        ServerConfigurationController(
          initialConfiguration: ServerConfiguration.parse(
            'https://shangan.test',
          ),
          store: _MemoryStore(),
          tokenStore: MemoryTokenStore(),
        ),
      ),
    ],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(home: AboutPage()),
    ),
  );
  await tester.pump();
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 50));
}

final class _MemoryStore implements ServerConfigurationStore {
  @override
  Future<ServerConfiguration> load({required String defaultBaseUrl}) async =>
      ServerConfiguration.parse(defaultBaseUrl);

  @override
  Future<void> save(ServerConfiguration configuration) async {}
}
