import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/focus/presentation/focus_duration_sheet.dart';

void main() {
  testWidgets('自定义时长使用横向滑条，单次手势内连续拖动持续生效', (tester) async {
    var selected = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: FocusDurationPicker(
            onSelected: (seconds) => selected = seconds,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final slider = find.byKey(const Key('focusCustomDurationSlider'));
    expect(slider, findsOneWidget);
    expect(find.text('30 分钟'), findsWidgets);

    // 手指不离开屏幕连续向右推进，这正是圆形拖拽版本第一次之后失效的场景。
    final gesture = await tester.startGesture(tester.getCenter(slider));
    await tester.pump();
    var previous = _labelMinutes(tester);
    for (var step = 0; step < 5; step++) {
      await gesture.moveBy(const Offset(12, 0));
      await tester.pump();
      final current = _labelMinutes(tester);
      expect(current, greaterThan(previous), reason: '第 ${step + 1} 次拖动未生效');
      previous = current;
    }

    // 反向拖动同样必须生效。
    await gesture.moveBy(const Offset(-30, 0));
    await tester.pump();
    expect(_labelMinutes(tester), lessThan(previous));

    await gesture.up();
    await tester.pumpAndSettle();

    final finalMinutes = _labelMinutes(tester);
    await tester.tap(find.byKey(const Key('startCustomFocusDuration')));
    await tester.pumpAndSettle();
    expect(selected, finalMinutes * 60);
  });

  testWidgets('极小幅度拖动也会累积改变时长', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: FocusDurationPicker(onSelected: (_) {})),
      ),
    );
    await tester.pumpAndSettle();

    final slider = find.byKey(const Key('focusCustomDurationSlider'));
    final gesture = await tester.startGesture(tester.getCenter(slider));
    await tester.pump();
    final start = _labelMinutes(tester);

    // 每帧只移动 3 像素，圆形版本会因为 round 截断把这些增量全部丢弃。
    for (var step = 0; step < 6; step++) {
      await gesture.moveBy(const Offset(3, 0));
      await tester.pump();
    }
    expect(_labelMinutes(tester), greaterThan(start));

    await gesture.up();
    await tester.pumpAndSettle();
  });

  testWidgets('拖动时滑条上方浮起指示器带弹性入场动画，松手后收起', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: FocusDurationPicker(onSelected: (_) {})),
      ),
    );
    await tester.pumpAndSettle();

    final bubble = find.byKey(const Key('focusCustomDurationBubble'));
    expect(bubble, findsNothing);

    final slider = find.byKey(const Key('focusCustomDurationSlider'));
    final gesture = await tester.startGesture(tester.getCenter(slider));
    await gesture.moveBy(const Offset(30, 0));
    await tester.pump();

    expect(bubble, findsOneWidget);
    // 刚出现时缩放动画尚未走完，稳定后必须达到 1.0。
    final entering = tester.widget<ScaleTransition>(bubble).scale.value;
    expect(entering, lessThan(1.0));
    await tester.pumpAndSettle();
    expect(tester.widget<ScaleTransition>(bubble).scale.value, 1.0);

    // 指示器必须位于滑条上方，避免被手指遮挡。
    expect(
      tester.getBottomLeft(bubble).dy,
      lessThanOrEqualTo(tester.getTopLeft(slider).dy),
    );
    // 指示器水平跟随滑块，向右拖动后应位于滑条中线右侧。
    expect(
      tester.getCenter(bubble).dx,
      greaterThan(tester.getCenter(slider).dx),
    );
    // 指示器上的时长必须与标签一致。
    expect(
      find.descendant(of: bubble, matching: find.text(_label(tester))),
      findsOneWidget,
    );

    await gesture.up();
    await tester.pumpAndSettle();
    expect(bubble, findsNothing);
  });

  testWidgets('滑条在可滚动弹层内水平拖动不会被滚动手势抢走', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            height: 300,
            child: FocusDurationPicker(onSelected: (_) {}),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final slider = find.byKey(const Key('focusCustomDurationSlider'));
    await tester.scrollUntilVisible(
      slider,
      120,
      scrollable: find.byType(Scrollable).first,
    );
    final gesture = await tester.startGesture(tester.getCenter(slider));
    await tester.pump();
    final before = _labelMinutes(tester);
    await gesture.moveBy(const Offset(40, 0));
    await tester.pump();
    expect(_labelMinutes(tester), greaterThan(before));
    await gesture.up();
    await tester.pumpAndSettle();
  });

  test('时长文案覆盖分钟、整小时和小时加分钟三种写法', () {
    expect(formatFocusDurationLabel(1), '1 分钟');
    expect(formatFocusDurationLabel(59), '59 分钟');
    expect(formatFocusDurationLabel(60), '1 小时');
    expect(formatFocusDurationLabel(90), '1 小时 30 分钟');
    expect(formatFocusDurationLabel(720), '12 小时');
  });
}

String _label(WidgetTester tester) => tester
    .widget<Text>(find.byKey(const Key('focusCustomDurationLabel')))
    .data!;

/// 从标签读取当前分钟数，兼容「x 小时 y 分钟」「x 小时」「y 分钟」三种写法。
int _labelMinutes(WidgetTester tester) {
  final label = _label(tester);
  final hourMatch = RegExp(r'(\d+) 小时').firstMatch(label);
  final minuteMatch = RegExp(r'(\d+) 分钟').firstMatch(label);
  final hours = hourMatch == null ? 0 : int.parse(hourMatch.group(1)!);
  final minutes = minuteMatch == null ? 0 : int.parse(minuteMatch.group(1)!);
  return hours * 60 + minutes;
}
