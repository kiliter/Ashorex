import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/course_addition_models.dart';
import 'package:shangan_ios/features/home/presentation/course_addition_confirmation.dart';

/// 确认弹窗只产出选择，不主动提交任何请求；取消必须与确认空列表区分。
void main() {
  const old = HistoryCourseTodo(
    id: 'old',
    localDate: '2026-09-07',
    title: '第一讲',
    targetProgressPermille: 500,
    progressPositionMs: 60000,
  );
  const item = CourseAdditionItem(
    resourceId: 'lesson',
    title: '第一讲',
    status: 'HISTORY',
    history: [old],
  );

  testWidgets('批量重复课时默认各新增一条复习', (tester) async {
    List<String>? answer;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              answer = await confirmCourseAdditions(context, [
                const CourseAdditionItem(
                  resourceId: 'a',
                  title: '第一讲',
                  status: 'EXISTING',
                  history: [],
                  reviewAvailable: true,
                ),
                const CourseAdditionItem(
                  resourceId: 'b',
                  title: '第二讲',
                  status: 'NEW',
                  history: [],
                  reviewAvailable: true,
                ),
                const CourseAdditionItem(
                  resourceId: 'c',
                  title: '第三讲',
                  status: 'NEW',
                  history: [],
                ),
              ]);
            },
            child: const Text('打开'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();
    expect(find.text('普通课时 1 个；可新增复习 2 个。'), findsOneWidget);
    await tester.tap(find.text('确认添加'));
    await tester.pumpAndSettle();
    expect(answer, ['review:a', 'review:b']);
  });

  testWidgets('取消历史顺延返回 null，不形成写操作确认', (tester) async {
    List<String>? answer = ['not-returned'];
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              answer = await confirmCourseAdditions(context, [item]);
            },
            child: const Text('打开'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();
    expect(find.textContaining('目标取两者较高值'), findsOneWidget);
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(answer, isNull);
  });

  testWidgets('确认唯一历史项返回原 ID，不创建替代身份', (tester) async {
    List<String>? answer;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              answer = await confirmCourseAdditions(context, [item]);
            },
            child: const Text('打开'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认添加'));
    await tester.pumpAndSettle();
    expect(answer, ['old']);
  });

  testWidgets('多条历史默认跳过，不能任意选择或合并', (tester) async {
    List<String>? answer;
    const duplicate = CourseAdditionItem(
      resourceId: 'lesson',
      title: '第一讲',
      status: 'HISTORY',
      history: [
        old,
        HistoryCourseTodo(
          id: 'another',
          localDate: '2026-09-06',
          title: '第一讲',
          targetProgressPermille: 1000,
          progressPositionMs: 0,
        ),
      ],
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              answer = await confirmCourseAdditions(context, [duplicate]);
            },
            child: const Text('打开'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认添加'));
    await tester.pumpAndSettle();
    expect(answer, isEmpty);
  });
}
