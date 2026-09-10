import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log_redactor.dart';

void main() {
  const redactor = DiagnosticLogRedactor();

  test('去掉 Bearer、查询串、JWT 与口令，保留路径', () {
    const raw = '''
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.aaa.bbb
GET /api/v1/me?access_token=xyz
password=hunter2
api_key=emby-secret
''';
    final out = redactor.redact(raw);
    expect(out, isNot(contains('eyJhbGciOiJIUzI1NiJ9')));
    expect(out, isNot(contains('access_token=xyz')));
    expect(out, isNot(contains('hunter2')));
    expect(out, isNot(contains('emby-secret')));
    expect(out, contains('[REDACTED]'));
    expect(out, contains('GET /api/v1/me'));
  });

  test('pathOnly 丢弃查询串', () {
    expect(
      redactor.pathOnly('https://example.test/api/v1/me?token=abc'),
      '/api/v1/me',
    );
  });
}
