import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/profile/presentation/app_update_page.dart';
import '../../support/fake_backend.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/app_release.dart';

void main() {
  for (final status in [404, 503]) {
    testWidgets('查询 $status 显示错误且不伪报无需更新', (tester) async {
      const channel = MethodChannel('com.shangan/app-update');
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (call) async => {'version': '2.5.0', 'simulator': true},
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          null,
        ),
      );
      final backend = FakeBackend()
        ..on('GET', '/api/v1/app-updates/latest', status: status);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            shanganRepositoryProvider.overrideWithValue(
              buildRepository(backend),
            ),
          ],
          child: const MaterialApp(home: AppUpdatePage()),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('检查更新'));
      await tester.pumpAndSettle();
      expect(find.text('当前无需更新'), findsNothing);
      expect(backend.callCount('GET', '/api/v1/app-updates/latest'), 1);
      expect(
        find.text(status == 404 ? '当前服务端暂不支持检查更新，请联系管理员更新服务端' : '假后端按测试要求返回失败'),
        findsOneWidget,
      );
      await tester.tap(find.text('检查更新'));
      await tester.pumpAndSettle();
      expect(backend.callCount('GET', '/api/v1/app-updates/latest'), 2);
    });
  }

  testWidgets('打开页面及恢复前台不查询，只有点击按钮才查询', (tester) async {
    const channel = MethodChannel('com.shangan/app-update');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async => {'version': '2.5.0', 'simulator': true},
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    final backend = FakeBackend();
    backend.on(
      'GET',
      '/api/v1/app-updates/latest',
      json: {
        'version': '2.6.0',
        'notes': '新版本说明',
        'downloadable': true,
        'size': 100,
        'sha256': 'a' * 64,
        'downloadPath': '/api/v1/app-updates/releases/v2.6.0/assets/ios',
        'pagePath': '/downloads/app/v2.6.0/ios',
      },
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: const MaterialApp(home: AppUpdatePage()),
      ),
    );
    await tester.pumpAndSettle();
    expect(backend.requests, isEmpty);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(backend.requests, isEmpty);
    await tester.tap(find.text('检查更新'));
    await tester.pumpAndSettle();
    expect(backend.callCount('GET', '/api/v1/app-updates/latest'), 1);
    expect(find.text('发现新版本 2.6.0'), findsOneWidget);
    expect(find.text('新版本说明'), findsOneWidget);
  });

  test('更新只按数值版本比较，不使用构建号或字符串排序', () {
    expect(AppRelease.compare('2.10.0', '2.9.0'), greaterThan(0));
    expect(AppRelease.compare('2.6.0', '2.6.0'), 0);
    expect(AppRelease.compare('2.5.0', '2.6.0'), lessThan(0));
    expect(() => AppRelease.compare('2.6.0+9', '2.6.0'), throwsFormatException);
    expect(() => AppRelease.compare('v2.6.0', '2.6.0'), throwsFormatException);
  });
}
