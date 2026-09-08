import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/device/attachment_picker.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/home/presentation/attachment_editor.dart';

import '../../support/fake_attachment_picker.dart';
import '../../support/fake_backend.dart';

/// 附件区是「完成凭证必填」（requireEvidence）唯一的解除入口，
/// 因此必须验证：真实清单渲染、multipart 上传载荷、三个错误码的中文文案、
/// 删除后清单刷新，以及带 Bearer 的缩略图下载。全部走假后端与假选择器。
void main() {
  const todoId = 't-1';
  const listPath = '/api/v1/todos/$todoId/attachments';
  const attachmentJson = {
    'id': 'a-1',
    'filename': 'proof.png',
    'contentType': 'image/png',
    'sizeBytes': 2048,
    'sortOrder': 0,
    'createdAt': '2026-09-07T01:00:00Z',
    'downloadUrl': '/api/v1/todos/t-1/attachments/a-1/content',
  };

  testWidgets('清单为空时只渲染添加入口，并向外回报 0 个附件', (tester) async {
    final backend = FakeBackend()..on('GET', listPath, json: const []);
    final counts = <int>[];
    await _pump(tester, backend, FakeAttachmentPicker(), counts: counts);

    expect(find.text('添加'), findsOneWidget);
    expect(counts, [0]);
  });

  testWidgets('上传真的发出 multipart 请求，字段名为 file 且带 Content-Type', (tester) async {
    final backend = FakeBackend()
      ..on('GET', listPath, json: const [])
      ..on('POST', listPath, json: attachmentJson);
    final picker = FakeAttachmentPicker(result: pickedPng());
    final counts = <int>[];
    await _pump(tester, backend, picker, counts: counts);

    // 清单布局只有一个「添加」，展开来源列表后选「拍照」。
    await tester.tap(find.text('添加'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('拍照'));
    await tester.pumpAndSettle();

    expect(picker.requested, [AttachmentSource.camera]);
    final request = backend.lastRequest('POST', listPath);
    final form = request.body! as FormData;
    expect(form.files.single.key, 'file');
    expect(form.files.single.value.filename, 'proof.png');
    expect(form.files.single.value.contentType?.mimeType, 'image/png');
    expect(form.files.single.value.length, onePixelPng.length);
  });

  testWidgets('对话框布局的「选文件」入口走文件来源', (tester) async {
    final backend = FakeBackend()
      ..on('GET', listPath, json: const [])
      ..on('POST', listPath, json: attachmentJson);
    final picker = FakeAttachmentPicker(result: pickedPng());
    await _pump(tester, backend, picker, layout: AttachmentEditorLayout.dialog);

    expect(find.text('拍照'), findsOneWidget);
    await tester.tap(find.text('选文件'));
    await tester.pumpAndSettle();

    expect(picker.requested, [AttachmentSource.file]);
    expect(backend.callCount('POST', listPath), 1);
  });

  testWidgets('上传成功后清单刷新并回报真实数量', (tester) async {
    final backend = FakeBackend()
      ..on('GET', listPath, json: const [])
      ..on('POST', listPath, json: attachmentJson)
      ..on('GET', '$listPath/a-1/content', bytes: onePixelPng);
    final counts = <int>[];
    await _pump(
      tester,
      backend,
      FakeAttachmentPicker(result: pickedPng()),
      counts: counts,
      layout: AttachmentEditorLayout.dialog,
    );
    expect(counts, [0]);

    // 上传后清单接口改为返回这条附件，验证 UI 用的是服务端真相而不是本地推算。
    backend.on('GET', listPath, json: const [attachmentJson]);
    await tester.tap(find.text('拍照'));
    await tester.pumpAndSettle();

    expect(counts, [0, 1]);
    expect(backend.callCount('GET', '$listPath/a-1/content'), 1);
  });

  testWidgets('缩略图经仓库下载字节，不使用 Image.network', (tester) async {
    final backend = FakeBackend()
      ..on('GET', listPath, json: const [attachmentJson])
      ..on('GET', '$listPath/a-1/content', bytes: onePixelPng);
    await _pump(tester, backend, FakeAttachmentPicker());
    await tester.pumpAndSettle();

    expect(backend.callCount('GET', '$listPath/a-1/content'), 1);
    expect(find.byType(Image), findsOneWidget);
    // 页面不得直接用 Image.network：它不会携带 Bearer Token。
    expect(tester.widget<Image>(find.byType(Image)).image, isA<MemoryImage>());
  });

  testWidgets('用户取消选择时不发起上传', (tester) async {
    final backend = FakeBackend()..on('GET', listPath, json: const []);
    await _pump(
      tester,
      backend,
      FakeAttachmentPicker(),
      layout: AttachmentEditorLayout.dialog,
    );

    await tester.tap(find.text('拍照'));
    await tester.pumpAndSettle();

    expect(backend.callCount('POST', listPath), 0);
    expect(find.textContaining('附件'), findsNothing);
  });

  testWidgets('ATTACHMENT_TOO_LARGE 映射为「单个附件不能超过 10MB」', (tester) async {
    await _expectUploadError(
      tester,
      errorCode: 'ATTACHMENT_TOO_LARGE',
      status: 400,
      message: '单个附件不能超过 10MB',
    );
  });

  testWidgets('ATTACHMENT_TYPE_UNSUPPORTED 映射为「只支持图片或 PDF 附件」', (tester) async {
    await _expectUploadError(
      tester,
      errorCode: 'ATTACHMENT_TYPE_UNSUPPORTED',
      status: 400,
      message: '只支持图片或 PDF 附件',
    );
  });

  testWidgets('ATTACHMENT_LIMIT_REACHED 映射为「单条待办最多 9 个附件」', (tester) async {
    await _expectUploadError(
      tester,
      errorCode: 'ATTACHMENT_LIMIT_REACHED',
      status: 409,
      message: '单条待办最多 9 个附件',
    );
  });

  testWidgets('删除附件后清单刷新且回报数量下降', (tester) async {
    final backend = FakeBackend()
      ..on('GET', listPath, json: const [attachmentJson])
      ..on('GET', '$listPath/a-1/content', bytes: onePixelPng)
      ..on('DELETE', '$listPath/a-1');
    final counts = <int>[];
    await _pump(tester, backend, FakeAttachmentPicker(), counts: counts);
    await tester.pumpAndSettle();
    expect(counts, [1]);

    await tester.tap(find.byType(Image));
    await tester.pumpAndSettle();
    expect(find.text('proof.png'), findsOneWidget);

    backend.on('GET', listPath, json: const []);
    await tester.tap(find.text('删除附件'));
    await tester.pumpAndSettle();

    expect(backend.callCount('DELETE', '$listPath/a-1'), 1);
    expect(counts, [1, 0]);
    expect(find.byType(Image), findsNothing);
  });

  testWidgets('清单接口失败时不回报数量，避免把用户锁在无法完成状态', (tester) async {
    final backend = FakeBackend()
      ..on('GET', listPath, status: 503, errorCode: 'SERVICE_UNAVAILABLE');
    final counts = <int>[];
    await _pump(tester, backend, FakeAttachmentPicker(), counts: counts);
    await tester.pumpAndSettle();

    expect(counts, isEmpty);
    // 展示错误但不禁掉添加入口：用户仍能补一个凭证把 Todo 推到完成。
    expect(find.byIcon(Icons.error_outline), findsOneWidget);
    expect(find.text('添加'), findsOneWidget);
  });

  testWidgets('达到 9 个上限后隐藏添加入口', (tester) async {
    final attachments = [
      for (var index = 0; index < 9; index++)
        {...attachmentJson, 'id': 'a-$index', 'sortOrder': index},
    ];
    final backend = FakeBackend()..on('GET', listPath, json: attachments);
    await _pump(tester, backend, FakeAttachmentPicker());
    await tester.pumpAndSettle();

    expect(find.text('添加'), findsNothing);
  });
}

/// 上传失败时错误码到中文文案的映射断言。
Future<void> _expectUploadError(
  WidgetTester tester, {
  required String errorCode,
  required int status,
  required String message,
}) async {
  const listPath = '/api/v1/todos/t-1/attachments';
  final backend = FakeBackend()
    ..on('GET', listPath, json: const [])
    ..on('POST', listPath, status: status, errorCode: errorCode);
  await _pump(
    tester,
    backend,
    FakeAttachmentPicker(result: pickedPng()),
    layout: AttachmentEditorLayout.dialog,
  );

  await tester.tap(find.text('拍照'));
  await tester.pumpAndSettle();

  expect(find.text(message), findsOneWidget);
}

Future<void> _pump(
  WidgetTester tester,
  FakeBackend backend,
  FakeAttachmentPicker picker, {
  List<int>? counts,
  AttachmentEditorLayout layout = AttachmentEditorLayout.sheet,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        attachmentPickerProvider.overrideWithValue(picker),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: AttachmentEditor(
              todoId: 't-1',
              layout: layout,
              onCountChanged: (count) => counts?.add(count),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}
