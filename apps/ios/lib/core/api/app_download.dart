import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import '../models/app_release.dart';

/// 单个 APK 下载会话：仅固定同源地址、断点续传和完整文件校验。
final class AppDownload {
  final CancelToken _cancel = CancelToken();
  void _checkCancelled() {
    if (_cancel.isCancelled) throw const FormatException('已取消下载');
  }

  void cancel() => _cancel.cancel('用户取消下载');

  /// 部分文件与摘要绑定，服务器忽略 Range 时覆盖重下，绝不追加错误内容。
  Future<String> run(
    Uri url,
    String directory,
    AppRelease release,
    void Function(int, int) progress,
  ) async {
    final file = File('$directory/update-${release.sha256}.apk');
    await file.parent.create(recursive: true);
    final client = Dio(
      BaseOptions(
        connectTimeout: const Duration(seconds: 15),
        receiveTimeout: const Duration(seconds: 30),
      ),
    );
    try {
      var offset = await file.exists() ? await file.length() : 0;
      if (offset > release.size) {
        await file.delete();
        offset = 0;
      }
      if (offset < release.size) {
        final response = await client.get<ResponseBody>(
          url.toString(),
          cancelToken: _cancel,
          options: Options(
            responseType: ResponseType.stream,
            followRedirects: false,
            headers: {if (offset > 0) 'Range': 'bytes=$offset-'},
            validateStatus: (code) => code == 200 || code == 206,
          ),
        );
        if (response.statusCode == 206) {
          final contentRange = response.headers.value('content-range');
          if (contentRange !=
              'bytes $offset-${release.size - 1}/${release.size}') {
            await response.data!.stream.listen(null).cancel();
            throw const FormatException('下载范围不一致，请重试');
          }
        } else {
          offset = 0;
        }
        final output = await file.open(
          mode: offset > 0 ? FileMode.append : FileMode.write,
        );
        try {
          progress(offset, release.size);
          await for (final chunk in response.data!.stream) {
            _checkCancelled();
            offset += chunk.length;
            if (offset > release.size) throw const FormatException('安装包大小不一致');
            await output.writeFrom(chunk);
            progress(offset, release.size);
          }
        } finally {
          await output.close();
        }
      }
      _checkCancelled();
      if (await file.length() != release.size) {
        throw const FormatException('下载未完成，请重试');
      }
      final hash = await sha256.bind(file.openRead()).first;
      if (hash.toString() != release.sha256) {
        await file.delete();
        throw const FormatException('安装包校验失败，请重新下载');
      }
      _checkCancelled();
      return file.path;
    } finally {
      client.close(force: true);
    }
  }
}
