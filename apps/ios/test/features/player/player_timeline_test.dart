import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/player/presentation/player_timeline.dart';

void main() {
  testWidgets('进度条展示实际播放位置，但点击和拖拽都不会改变进度', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: PlayerTimeline(
            duration: Duration(seconds: 100),
            position: Duration(seconds: 35),
            maximumSeek: Duration(seconds: 30),
          ),
        ),
      ),
    );
    final slider = tester.widget<Slider>(find.byType(Slider));
    expect(slider.value, 35000);
    expect(slider.onChanged, isNull);
    expect(slider.onChangeStart, isNull);
    expect(slider.onChangeEnd, isNull);
    await tester.tap(find.byType(Slider));
    await tester.drag(find.byType(Slider), const Offset(300, 0));
    await tester.pump();
    expect(tester.widget<Slider>(find.byType(Slider)).value, 35000);
  });
}
