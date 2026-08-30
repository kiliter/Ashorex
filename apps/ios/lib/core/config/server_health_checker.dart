import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';

/// 可安全展示的服务端连接异常，不包含响应正文或底层堆栈。
final class ServerConnectionException implements Exception {
  const ServerConnectionException(this.message);

  final String message;

  @override
  String toString() => message;
}

abstract interface class ServerHealthChecker {
  Future<void> check(ServerConfiguration configuration);
}

/// 使用独立且无认证的 Dio 请求检查目标服务，不复用当前登录会话。
final class DioServerHealthChecker implements ServerHealthChecker {
  @override
  Future<void> check(ServerConfiguration configuration) async {
    final dio = Dio(
      BaseOptions(
        baseUrl: configuration.baseUrl,
        connectTimeout: const Duration(seconds: 5),
        receiveTimeout: const Duration(seconds: 5),
        responseType: ResponseType.json,
      ),
    );
    try {
      final response = await dio.get<dynamic>('/actuator/health');
      final statusCode = response.statusCode ?? 0;
      final data = response.data;
      final healthy =
          statusCode >= 200 &&
          statusCode < 300 &&
          data is Map &&
          data['status'] == 'UP';
      if (!healthy) {
        throw const ServerConnectionException('服务端健康检查未通过');
      }
    } on ServerConnectionException {
      rethrow;
    } on DioException {
      throw const ServerConnectionException('无法连接服务器，请检查地址和网络');
    } finally {
      dio.close(force: true);
    }
  }
}

final serverHealthCheckerProvider = Provider<ServerHealthChecker>((ref) {
  return DioServerHealthChecker();
});
