import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/layout/adaptive_scaffold.dart';

void main() {
  const destinations = [
    AdaptiveDestination(icon: Icons.home_outlined, label: '首页'),
    AdaptiveDestination(icon: Icons.menu_book_outlined, label: '学习'),
  ];

  Future<void> pumpAt(WidgetTester tester, Size size) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    await tester.pumpWidget(
      MaterialApp(
        home: AdaptiveScaffold(
          selectedIndex: 0,
          onDestinationSelected: (_) {},
          destinations: destinations,
          accountName: '张三',
          body: const SizedBox.expand(),
        ),
      ),
    );
    await tester.pump();
  }

  testWidgets('390x844 手机使用 BottomNavigation', (tester) async {
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await pumpAt(tester, const Size(390, 844));
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(
      find.byKey(const ValueKey('adaptive-navigation-rail')),
      findsNothing,
    );
  });

  testWidgets('844x390 手机横屏仍使用 BottomNavigation', (tester) async {
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await pumpAt(tester, const Size(844, 390));
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(
      find.byKey(const ValueKey('adaptive-navigation-rail')),
      findsNothing,
    );
  });

  testWidgets('820x1180 Pad 使用品牌侧栏而不是底栏', (tester) async {
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await pumpAt(tester, const Size(820, 1180));
    expect(
      find.byKey(const ValueKey('adaptive-navigation-rail')),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('pad-app-icon')), findsOneWidget);
    expect(find.text('张'), findsOneWidget);
    expect(find.text('三'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
  });

  testWidgets('Pad 横屏侧栏保留首页等四个目的地', (tester) async {
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await pumpAt(tester, const Size(1180, 820));
    expect(
      find.byKey(const ValueKey('adaptive-navigation-rail')),
      findsOneWidget,
    );
    expect(find.text('首页'), findsWidgets);
    expect(find.text('学习'), findsWidgets);
  });
}
