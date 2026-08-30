import 'package:dio/dio.dart';

/// 客户端可安全展示的 API 异常，不包含服务端堆栈和敏感请求头。
final class ApiException implements Exception {
  const ApiException({
    required this.statusCode,
    required this.errorCode,
    required this.message,
  });

  final int? statusCode;
  final String errorCode;
  final String message;

  factory ApiException.fromDio(DioException exception) {
    final data = exception.response?.data;
    final problem = data is Map ? Map<String, dynamic>.from(data) : null;
    return ApiException(
      statusCode: exception.response?.statusCode,
      errorCode: problem?['errorCode'] as String? ?? 'NETWORK_ERROR',
      message:
          problem?['detail'] as String? ??
          problem?['title'] as String? ??
          '网络请求失败，请稍后重试',
    );
  }

  @override
  String toString() => message;
}
