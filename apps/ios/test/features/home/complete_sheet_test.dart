import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/device/attachment_picker.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/complete_sheet.dart';

import '../../support/fake_attachment_picker.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 完成回填面板是 AGENTS.md 点名的关键确认流程：凭证必填与补记备注必填
/// 两条约束都不能被绕过，一键标签必须真正进入提交载荷。
void main() {
  testWidgets('要求凭证但没有附件时不能标记完成', (tester) async {
    final backend = FakeBackend();
    await _pump(
      tester,
      backend,
      todo: todoItem(id: 't-1', requireEvidence: true, attachmentCount: 0),
    );

    expect(find.text('完成凭证（必填）'), findsOneWidget);
    expect(find.text('还需上传至少 1 个凭证才能标记完成'), findsOneWidget);
    expect(_saveButton(tester).onPressed, isNull);
    expect(backend.callCount('POST', '/api/v1/todos/t-1/complete'), 0);
  });

  testWidgets('已上传凭证后允许完成', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-1/complete');
    await _pump(
      tester,
      backend,
      todo: todoItem(id: 't-1', requireEvidence: true, attachmentCount: 1),
      attachments: const [_attachmentJson],
    );

    expect(find.text('还需上传至少 1 个凭证才能标记完成'), findsNothing);
    expect(_saveButton(tester).onPressed, isNotNull);

    await tester.tap(find.text('保存并完成'));
    await tester.pumpAndSettle();
    expect(backend.callCount('POST', '/api/v1/todos/t-1/complete'), 1);
  });

  testWidgets('凭证必填时以服务端附件清单为准，快照有附件但清单为空仍然禁用', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-1/complete');
    await _pump(
      tester,
      backend,
      todo: todoItem(id: 't-1', requireEvidence: true, attachmentCount: 1),
      attachments: const [],
    );

    expect(find.text('还需上传至少 1 个凭证才能标记完成'), findsOneWidget);
    expect(_saveButton(tester).onPressed, isNull);
  });

  testWidgets('凭证必填时上传成功后按钮转为可用', (tester) async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/todos/t-1/attachments', json: _attachmentJson)
      ..on('POST', '/api/v1/todos/t-1/complete');
    await _pump(
      tester,
      backend,
      // 待办事项走原型 3-4 的对话框布局，附件区直接给「拍照 / 选文件」。
      todo: todoItem(
        id: 't-1',
        type: TodoType.task,
        requireEvidence: true,
        attachmentCount: 0,
      ),
      picker: FakeAttachmentPicker(result: pickedPng()),
    );
    expect(_saveButton(tester).onPressed, isNull);

    backend.on(
      'GET',
      '/api/v1/todos/t-1/attachments',
      json: const [_attachmentJson],
    );
    await tester.tap(find.text('拍照'));
    await tester.pumpAndSettle();

    expect(backend.callCount('POST', '/api/v1/todos/t-1/attachments'), 1);
    expect(find.text('还需上传至少 1 个凭证才能标记完成'), findsNothing);
    expect(_saveButton(tester).onPressed, isNotNull);
  });

  testWidgets('补记完成时备注必填', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-1/complete');
    await _pump(tester, backend, todo: todoItem(id: 't-1'), backfill: true);

    expect(find.textContaining('补记完成'), findsOneWidget);
    expect(_saveButton(tester).onPressed, isNull);

    await tester.enterText(find.byType(TextField), '晚上补看完了');
    await tester.pump();
    expect(_saveButton(tester).onPressed, isNotNull);

    await tester.tap(find.text('保存并完成'));
    await tester.pumpAndSettle();
    final request = backend.lastRequest('POST', '/api/v1/todos/t-1/complete');
    expect(request.json['backfill'], isTrue);
    expect(request.json['note'], '晚上补看完了');
  });

  testWidgets('一键标签会追加到备注并写入提交载荷', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-1/complete');
    await _pump(tester, backend, todo: todoItem(id: 't-1'));

    await tester.tap(find.text('已掌握'));
    await tester.pump();
    await tester.tap(find.text('有疑问'));
    await tester.pump();
    await tester.tap(find.text('保存并完成'));
    await tester.pumpAndSettle();

    final request = backend.lastRequest('POST', '/api/v1/todos/t-1/complete');
    expect(request.json['noteTags'], [
      NoteTag.mastered.wire,
      NoteTag.hasQuestion.wire,
    ]);
    expect(request.json['note'], contains('已掌握'));
    expect(request.json['note'], contains('有疑问'));
  });

  testWidgets('再次点选同一标签会取消，不会重复写入', (tester) async {
    final backend = FakeBackend()..on('POST', '/api/v1/todos/t-1/complete');
    await _pump(tester, backend, todo: todoItem(id: 't-1'));

    await tester.tap(find.text('需重看'));
    await tester.pump();
    await tester.tap(find.text('需重看'));
    await tester.pump();
    await tester.tap(find.text('保存并完成'));
    await tester.pumpAndSettle();

    expect(
      backend
          .lastRequest('POST', '/api/v1/todos/t-1/complete')
          .json['noteTags'],
      isEmpty,
    );
  });

  testWidgets('保存失败时保留面板并展示错误', (tester) async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/t-1/complete',
        status: 409,
        errorCode: 'TODO_ALREADY_DONE',
      );
    await _pump(tester, backend, todo: todoItem(id: 't-1'));

    await tester.tap(find.text('保存并完成'));
    await tester.pumpAndSettle();

    expect(find.textContaining('保存失败'), findsOneWidget);
    expect(find.text('保存并完成'), findsOneWidget);
  });

  testWidgets('专注类型展示已专注时长而不是观看进度', (tester) async {
    await _pump(
      tester,
      FakeBackend(),
      todo: todoItem(
        id: 't-2',
        type: TodoType.focus,
        focusedMs: 25 * 60 * 1000,
        focusState: FocusState.finished,
      ),
    );

    expect(find.textContaining('已专注'), findsOneWidget);
    expect(find.textContaining('观看'), findsNothing);
  });
}

const _attachmentJson = {
  'id': 'a-1',
  'filename': 'proof.png',
  'contentType': 'image/png',
  'sizeBytes': 2048,
  'sortOrder': 0,
  'createdAt': '2026-09-07T01:00:00Z',
  'downloadUrl': '/api/v1/todos/t-1/attachments/a-1/content',
};

FilledButton _saveButton(WidgetTester tester) {
  return tester.widget<FilledButton>(
    find.ancestor(
      of: find.textContaining('保存'),
      matching: find.byType(FilledButton),
    ),
  );
}

Future<void> _pump(
  WidgetTester tester,
  FakeBackend backend, {
  required TodoItem todo,
  bool backfill = false,
  FakeAttachmentPicker? picker,
  List<Map<String, Object?>> attachments = const [],
}) async {
  // 附件区现在读真实清单，因此每个用例都要给出清单与缩略图字节。
  backend
    ..on('GET', '/api/v1/todos/${todo.id}/attachments', json: attachments)
    ..on(
      'GET',
      '/api/v1/todos/${todo.id}/attachments/a-1/content',
      bytes: onePixelPng,
    );
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        attachmentPickerProvider.overrideWithValue(
          picker ?? FakeAttachmentPicker(),
        ),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: CompleteSheet(todo: todo, backfill: backfill),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}
