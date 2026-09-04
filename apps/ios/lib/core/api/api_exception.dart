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

/// 优先展示服务端稳定业务错误文案；网络或解析失败时回退到调用方的场景化提示。
///
/// 服务端对版本冲突、项目不可修改、课时重复等场景都返回明确的 errorCode 与 detail，
/// 页面直接吞掉这些信息会让用户把业务冲突误判成网络故障。
String shanganErrorMessage(Object error, String fallback) {
  if (error is ApiException &&
      error.errorCode != 'NETWORK_ERROR' &&
      error.message.isNotEmpty) {
    return error.message;
  }
  return fallback;
}
