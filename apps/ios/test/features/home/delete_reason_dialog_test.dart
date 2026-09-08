import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/delete_reason_dialog.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 删除原因弹窗是 AGENTS.md 点名的关键确认流程：原因标签与说明字数都必填，
/// 且删除请求必须携带原因，否则服务端台账会缺失依据。
void main() {
  testWidgets('未选标签或说明不足时确认按钮禁用', (tester) async {
    final harness = await _open(
      tester,
      FakeBackend(),
      todos: [todoItem(id: 't-1', title: '行政法第 3 讲')],
    );

    expect(find.text('删除待办'), findsOneWidget);
    expect(find.text('行政法第 3 讲'), findsOneWidget);
    expect(_confirmButton(tester).onPressed, isNull);

    // 只写够字数但没选标签：仍不可提交。
    await tester.enterText(find.byType(TextField), '今天真的没时间做');
    await tester.pump();
    expect(_confirmButton(tester).onPressed, isNull);

    // 补上标签后才允许提交。
    await tester.tap(find.text('计划排太多'));
    await tester.pump();
    expect(_confirmButton(tester).onPressed, isNotNull);
    expect(harness.result, isNull);
  });

  testWidgets('说明字数不足最少字数时不可提交', (tester) async {
    await _open(
      tester,
      FakeBackend(),
      todos: [todoItem(id: 't-1')],
      minReasonLength: 5,
    );

    await tester.tap(find.text('今天临时有事'));
    await tester.pump();
    await tester.enterText(find.byType(TextField), '忙');
    await tester.pump();
    expect(_confirmButton(tester).onPressed, isNull);
    expect(find.textContaining('至少 5 个字'), findsOneWidget);

    await tester.enterText(find.byType(TextField), '临时加班到很晚');
    await tester.pump();
    expect(_confirmButton(tester).onPressed, isNotNull);
  });

  testWidgets('提交单条删除时把标签与说明一起发给服务端', (tester) async {
    final backend = FakeBackend()..on('DELETE', '/api/v1/todos/t-1');
    final harness = await _open(tester, backend, todos: [todoItem(id: 't-1')]);

    await tester.tap(find.text('加错了'));
    await tester.pump();
    await tester.enterText(find.byType(TextField), '重复添加了同一个课时');
    await tester.pump();
    await tester.tap(find.text('确认删除'));
    await tester.pumpAndSettle();

    final request = backend.lastRequest('DELETE', '/api/v1/todos/t-1');
    expect(request.json['reasonTag'], DeletionReasonTag.addedByMistake.wire);
    expect(request.json['reasonText'], '重复添加了同一个课时');
    expect(harness.result, isTrue);
    expect(find.byType(AlertDialog), findsNothing);
  });

  testWidgets('多选删除走批量接口并带上全部 ID', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/batch-delete');
    await _open(
      tester,
      backend,
      todos: [
        todoItem(id: 't-1'),
        todoItem(id: 't-2'),
      ],
    );

    expect(find.text('删除 2 项待办'), findsOneWidget);
    await tester.tap(find.text('不想学了'));
    await tester.pump();
    await tester.enterText(find.byType(TextField), '这周状态很差先放弃');
    await tester.pump();
    await tester.tap(find.text('确认删除'));
    await tester.pumpAndSettle();

    final request = backend.lastRequest('POST', '/api/v1/todos/batch-delete');
    expect(request.json['todoIds'], ['t-1', 't-2']);
    expect(request.json['reasonTag'], DeletionReasonTag.gaveUp.wire);
    expect(backend.callCount('DELETE', '/api/v1/todos/'), 0);
  });

  testWidgets('有督学人时提示会同步给督学人', (tester) async {
    await _open(
      tester,
      FakeBackend(),
      todos: [todoItem(id: 't-1')],
      supervisorName: '王五',
    );

    expect(find.textContaining('会同步给督学人 王五'), findsOneWidget);
  });

  testWidgets('服务端拒绝时保留弹窗并展示错误，不误判为删除成功', (tester) async {
    final backend = FakeBackend()
      ..on(
        'DELETE',
        '/api/v1/todos/t-1',
        status: 400,
        errorCode: 'TODO_DELETE_REASON_REQUIRED',
      );
    final harness = await _open(tester, backend, todos: [todoItem(id: 't-1')]);

    await tester.tap(find.text('加错了'));
    await tester.pump();
    await tester.enterText(find.byType(TextField), '点错了要撤销');
    await tester.pump();
    await tester.tap(find.text('确认删除'));
    await tester.pumpAndSettle();

    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.textContaining('删除失败'), findsOneWidget);
    expect(harness.result, isNull);
  });

  testWidgets('点击遮罩不能关闭；只有取消按钮能放弃删除', (tester) async {
    final harness = await _open(
      tester,
      FakeBackend(),
      todos: [todoItem(id: 't-1')],
    );

    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);

    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    expect(harness.result, isFalse);
  });
}

FilledButton _confirmButton(WidgetTester tester) {
  return tester.widget<FilledButton>(
    find.ancestor(of: find.text('确认删除'), matching: find.byType(FilledButton)),
  );
}

/// 以真实 `showDialog` 流程打开弹窗，这样才能验证 barrier 行为与返回值。
Future<_Harness> _open(
  WidgetTester tester,
  FakeBackend backend, {
  required List<TodoItem> todos,
  int minReasonLength = 5,
  String? supervisorName,
}) async {
  final harness = _Harness();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () async {
                harness.result = await DeleteReasonDialog.show(
                  context,
                  todos: todos,
                  minReasonLength: minReasonLength,
                  supervisorName: supervisorName,
                );
              },
              child: const Text('打开删除弹窗'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('打开删除弹窗'));
  await tester.pumpAndSettle();
  return harness;
}

final class _Harness {
  bool? result;
}
