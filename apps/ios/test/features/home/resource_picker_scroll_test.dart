import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/home/presentation/add_todo_sheet.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 验证共用加入课时弹窗的滚动边界，完成标准和提交始终可见。
void main() {
  testWidgets('长列表滚动不移动完成标准与加入按钮，目标可直接修改', (tester) async {
    await openPicker(tester, const Size(390, 844));
    expect(find.text('课时99'), findsNothing);
    await tester.tap(find.text('课时0'));
    await tester.pumpAndSettle();
    final scrollbar = tester.widget<RawScrollbar>(
      find.byKey(const ValueKey('resource-picker-scrollbar')),
    );
    expect(scrollbar.thumbVisibility, isTrue);
    expect(scrollbar.trackVisibility, isTrue);
    expect(scrollbar.interactive, isTrue);
    final target = tester.getTopLeft(find.text('完成标准'));
    final submit = tester.getTopLeft(find.text('加入待办'));
    await tester.drag(
      find.byKey(const ValueKey('resource-picker-list')),
      const Offset(0, -400),
    );
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(find.text('完成标准')), target);
    expect(tester.getTopLeft(find.text('加入待办')), submit);
    await tester.tap(find.text('看到 50%'));
    await tester.pumpAndSettle();
    expect(find.textContaining('已选 1 课时'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('拖动右侧滑块可定位长课时列表', (tester) async {
    await openPicker(tester, const Size(390, 844));
    final bar = tester.widget<RawScrollbar>(
      find.byKey(const ValueKey('resource-picker-scrollbar')),
    );
    await tester.dragFrom(
      tester.getTopRight(
            find.byKey(const ValueKey('resource-picker-scrollbar')),
          ) +
          const Offset(-2, 12),
      const Offset(0, 100),
    );
    await tester.pumpAndSettle();
    expect(bar.controller!.offset, greaterThan(0));
    expect(find.text('完成标准').hitTestable(), findsOneWidget);
  });

  testWidgets('自定义目标键盘下课时区域和固定标准不溢出', (tester) async {
    await openPicker(tester, const Size(390, 844));
    await tester.tap(find.text('自定义'));
    await tester.pumpAndSettle();
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    await tester.enterText(find.byType(TextField), '35');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(
      tester.getSize(find.byKey(const ValueKey('resource-picker-list'))).height,
      greaterThan(40),
    );
    expect(find.text('完成标准').hitTestable(), findsOneWidget);
    expect(find.text('加入待办').hitTestable(), findsOneWidget);
  });

  testWidgets('横屏键盘下自定义目标和加入按钮仍可见', (tester) async {
    await openPicker(tester, const Size(844, 390));
    await tester.tap(find.text('全部看完'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('自定义').last);
    await tester.pumpAndSettle();
    tester.view.viewInsets = const FakeViewPadding(bottom: 220);
    await tester.enterText(find.byType(TextField), '35');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('完成标准').hitTestable(), findsOneWidget);
    expect(find.text('加入待办').hitTestable(), findsOneWidget);
    expect(
      tester.getSize(find.byKey(const ValueKey('resource-picker-list'))).height,
      greaterThan(0),
    );
  });

  testWidgets('横屏固定完成标准且课时独立滚动', (tester) async {
    await openPicker(tester, const Size(844, 390));
    expect(tester.takeException(), isNull);
    expect(find.text('完成标准').hitTestable(), findsOneWidget);
    expect(
      tester.getSize(find.byKey(const ValueKey('resource-picker-list'))).height,
      greaterThan(40),
    );
  });
}

/// 仅替换协议数据；使用真实弹层、主题和选择逻辑。
Future<void> openPicker(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetViewInsets);
  final detail = CourseDetail.fromJson(
    courseDetailJson(
      id: 'c-1',
      resources: [
        for (var i = 0; i < 100; i++)
          courseResourceJson(id: 'r$i', title: '课时$i'),
      ],
    ),
  );
  final backend = FakeBackend()
    ..on('GET', '/api/v1/todos', json: dayViewJson());
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        courseDetailProvider('c-1').overrideWith((ref) async => detail),
      ],
      child: MaterialApp(
        theme: ShanganTheme.light(),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => showCourseResourcePicker(
                context,
                course: detail.summary,
                date: DateTime(2026, 9, 9),
              ),
              child: const Text('打开'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('打开'));
  await tester.pumpAndSettle();
}
