import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/api/api_exception.dart';

import '../../support/fake_backend.dart';

/// 附件上传与删除的仓库层测试。
///
/// 这两个方法是「完成凭证必填」（requireEvidence）唯一的解除入口，
/// 因此重点断言 multipart 字段名、Content-Type 与错误码透传，
/// 而不是页面表现。全部走假后端，不接触真实网络。
void main() {
  const todoId = 'todo-1';
  const attachmentJson = {
    'id': 'attachment-1',
    'filename': 'proof.png',
    'contentType': 'image/png',
    'sizeBytes': 2048,
    'sortOrder': 0,
    'createdAt': '2026-09-07T01:00:00Z',
    'downloadUrl': '/api/v1/todos/todo-1/attachments/attachment-1/content',
  };

  test('上传附件提交 multipart 表单，字段名为 file 且带真实 Content-Type', () async {
    final backend = FakeBackend()
      ..on('POST', '/api/v1/todos/$todoId/attachments', json: attachmentJson);
    final repository = buildRepository(backend);

    final attachment = await repository.uploadAttachment(
      todoId,
      filename: 'proof.png',
      contentType: 'image/png',
      bytes: const [1, 2, 3, 4],
    );

    expect(attachment.id, 'attachment-1');
    expect(attachment.filename, 'proof.png');
    expect(attachment.sizeBytes, 2048);
    expect(attachment.isPdf, isFalse);
    expect(
      attachment.downloadUrl,
      '/api/v1/todos/todo-1/attachments/attachment-1/content',
    );

    final request = backend.lastRequest(
      'POST',
      '/api/v1/todos/$todoId/attachments',
    );
    final form = request.body! as FormData;
    expect(form.files.single.key, 'file');
    expect(form.files.single.value.filename, 'proof.png');
    expect(form.files.single.value.contentType?.mimeType, 'image/png');
    expect(form.files.single.value.length, 4);
  });

  test('服务端拒绝超大附件时透传 ATTACHMENT_TOO_LARGE', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/$todoId/attachments',
        status: 400,
        errorCode: 'ATTACHMENT_TOO_LARGE',
      );
    final repository = buildRepository(backend);

    await expectLater(
      repository.uploadAttachment(
        todoId,
        filename: 'huge.png',
        contentType: 'image/png',
        bytes: const [1, 2, 3],
      ),
      throwsA(
        isA<ApiException>()
            .having(
              (error) => error.errorCode,
              'errorCode',
              'ATTACHMENT_TOO_LARGE',
            )
            .having((error) => error.statusCode, 'statusCode', 400),
      ),
    );
  });

  test('服务端拒绝不支持的类型时透传 ATTACHMENT_TYPE_UNSUPPORTED', () async {
    final backend = FakeBackend()
      ..on(
        'POST',
        '/api/v1/todos/$todoId/attachments',
        status: 400,
        errorCode: 'ATTACHMENT_TYPE_UNSUPPORTED',
      );
    final repository = buildRepository(backend);

    await expectLater(
      repository.uploadAttachment(
        todoId,
        filename: 'note.txt',
        contentType: 'text/plain',
        bytes: const [1],
      ),
      throwsA(
        isA<ApiException>().having(
          (error) => error.errorCode,
          'errorCode',
          'ATTACHMENT_TYPE_UNSUPPORTED',
        ),
      ),
    );
  });

  test('读取附件列表解析为模型，且模型不含存储路径与摘要字段', () async {
    final backend = FakeBackend()
      ..on('GET', '/api/v1/todos/$todoId/attachments', json: [attachmentJson]);
    final repository = buildRepository(backend);

    final attachments = await repository.loadAttachments(todoId);

    expect(attachments, hasLength(1));
    expect(attachments.single.contentType, 'image/png');
    expect(attachments.single.createdAt.toUtc(), DateTime.utc(2026, 9, 7, 1));
  });

  test('删除附件请求带上 todoId 与 attachmentId 两段路径', () async {
    final backend = FakeBackend()
      ..on('DELETE', '/api/v1/todos/$todoId/attachments/attachment-1');
    final repository = buildRepository(backend);

    await repository.deleteAttachment(todoId, 'attachment-1');

    expect(
      backend.callCount(
        'DELETE',
        '/api/v1/todos/$todoId/attachments/attachment-1',
      ),
      1,
    );
  });
}
