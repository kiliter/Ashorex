import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/home/presentation/complete_sheet.dart';
import 'package:shangan_ios/features/home/presentation/todo_row.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 待办事项（TASK）的完成路径：勾选即走完成回填对话框（原型 3-4 为居中对话框），
/// 要求凭证时必须先有附件；课时下架时只能补记完成或删除，不允许直接播放。
void main() {
  testWidgets('待办事项勾选后弹出居中的完成对话框', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-3/complete');
    await _pump(tester, backend, todo: _task());

    await tester.tap(find.bySemanticsLabel('开始 整理错题本'));
    await tester.pumpAndSettle();

    // TASK 走 Dialog 而不是底部弹层（原型 3-4）。
    expect(find.byType(Dialog), findsOneWidget);
    expect(find.byType(CompleteSheet), findsOneWidget);
    expect(find.text('待办事项'), findsOneWidget);
  });

  testWidgets('完成后提交备注与标签，并回调刷新', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-3/complete');
    var refreshed = 0;
    await _pump(
      tester,
      backend,
      todo: _task(),
      onChanged: () async => refreshed += 1,
    );

    await tester.tap(find.bySemanticsLabel('开始 整理错题本'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '错题本已整理完');
    await tester.pump();
    await tester.tap(find.text('已做笔记'));
    await tester.pump();
    await tester.tap(find.text('保存并完成'));
    await tester.pumpAndSettle();

    final body = backend.lastRequest('POST', '/api/v1/todos/t-3/complete').json;
    expect(body['noteTags'], [NoteTag.noted.wire]);
    expect(body['note'], contains('错题本已整理完'));
    expect(refreshed, 1);
  });

  testWidgets('要求凭证的待办事项在没有附件时无法完成', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-3/complete');
    await _pump(tester, backend, todo: _task(requireEvidence: true));

    await tester.tap(find.bySemanticsLabel('开始 整理错题本'));
    await tester.pumpAndSettle();

    expect(find.text('完成凭证（必填）'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(
            find.ancestor(
              of: find.text('保存并完成'),
              matching: find.byType(FilledButton),
            ),
          )
          .onPressed,
      isNull,
    );
    expect(backend.callCount('POST', '/api/v1/todos/t-3/complete'), 0);
  });

  testWidgets('已完成的待办再次点击进入备注编辑而不是重复完成', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-3/complete');
    await _pump(
      tester,
      backend,
      todo: _task(status: TodoStatus.done, note: '已经整理过'),
    );

    // 已完成行的勾不再触发「开始」主动作，避免重复完成。
    expect(find.bySemanticsLabel('开始 整理错题本'), findsNothing);
    await tester.tap(find.byType(ShanganTick));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-3/complete'), 0);
  });

  testWidgets('取消时不提交任何请求', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-3/complete');
    await _pump(tester, backend, todo: _task());

    await tester.tap(find.bySemanticsLabel('开始 整理错题本'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('稍后再填'));
    await tester.pumpAndSettle();

    expect(find.byType(CompleteSheet), findsNothing);
    expect(backend.callCount('POST', '/api/v1/todos/t-3/complete'), 0);
  });
}

TodoItem _task({
  bool requireEvidence = false,
  TodoStatus status = TodoStatus.todo,
  String note = '',
}) {
  return todoItem(
    id: 't-3',
    title: '整理错题本',
    type: TodoType.task,
    status: status,
    requireEvidence: requireEvidence,
    note: note,
  );
}

Future<void> _pump(
  WidgetTester tester,
  FakeBackend backend, {
  required TodoItem todo,
  Future<void> Function()? onChanged,
}) async {
  tester.view.physicalSize = const Size(1170, 2532);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: TodoRow(
            todo: todo,
            minReasonLength: 5,
            onChanged: onChanged ?? () async {},
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}
