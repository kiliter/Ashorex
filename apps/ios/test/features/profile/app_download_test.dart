import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/api/app_download.dart';
import 'package:shangan_ios/core/models/app_release.dart';

/// 真实本地 HTTP 字节协议，验证续传而不连接生产服务。
void main() {
  for (final partial in [true, false]) {
    test('续传时上游返回 ${partial ? 206 : 200} 都生成完整文件', () async {
      final bytes = [1, 2, 3, 4, 5, 6];
      final hash = sha256.convert(bytes).toString();
      final directory = await Directory.systemTemp.createTemp(
        'app-download-test',
      );
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() async {
        await server.close(force: true);
        await directory.delete(recursive: true);
      });
      await File(
        '${directory.path}/update-$hash.apk',
      ).writeAsBytes(bytes.take(3).toList());
      String? range;
      server.listen((request) async {
        range = request.headers.value('range');
        request.response.statusCode = partial ? 206 : 200;
        if (partial) {
          request.response.headers.set('content-range', 'bytes 3-5/6');
        }
        request.response.add(partial ? bytes.skip(3).toList() : bytes);
        await request.response.close();
      });
      final release = AppRelease.fromJson({
        'version': '2.6.0',
        'downloadable': true,
        'size': 6,
        'sha256': hash,
        'downloadPath': '',
        'pagePath': '',
      });
      final path = await AppDownload().run(
        Uri.parse('http://127.0.0.1:${server.port}/apk'),
        directory.path,
        release,
        (_, _) {},
      );
      expect(range, 'bytes=3-');
      expect(await File(path).readAsBytes(), bytes);
    });
  }
  test('摘要错误删除损坏的安装包', () async {
    final directory = await Directory.systemTemp.createTemp(
      'app-download-test',
    );
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() async {
      await server.close(force: true);
      await directory.delete(recursive: true);
    });
    server.listen((request) async {
      request.response.add([1, 2, 3]);
      await request.response.close();
    });
    final hash = 'a' * 64;
    final release = AppRelease.fromJson({
      'version': '2.6.0',
      'downloadable': true,
      'size': 3,
      'sha256': hash,
      'downloadPath': '',
      'pagePath': '',
    });
    await expectLater(
      AppDownload().run(
        Uri.parse('http://127.0.0.1:${server.port}/apk'),
        directory.path,
        release,
        (_, _) {},
      ),
      throwsFormatException,
    );
    expect(await File('${directory.path}/update-$hash.apk').exists(), isFalse);
  });
}
