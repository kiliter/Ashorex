import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/catalog/presentation/course_filter_panel.dart';

/// 面板草稿必须显式应用，取消不能串改调用方条件。
void main() {
  testWidgets('三类交集应用与取消保留原值', (tester) async {
    CatalogFilter? result;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () async {
                result = await showCourseFilterPanel(
                  context,
                  selected: const CatalogFilter(genre: '法律'),
                  genres: ['法律', '会计'],
                  people: ['老师甲'],
                  tags: ['重点'],
                );
              },
              child: const Text('打开'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('会计'));
    await tester.tap(find.text('人物'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('老师甲'));
    await tester.tap(find.text('标签'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('重点'));
    await tester.tap(find.text('应用筛选'));
    await tester.pumpAndSettle();
    expect(result?.genre, '会计');
    expect(result?.person, '老师甲');
    expect(result?.tag, '重点');
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('清空选择'));
    await tester.tap(find.byTooltip('取消筛选'));
    await tester.pumpAndSettle();
    expect(result, isNull);
  });
}
