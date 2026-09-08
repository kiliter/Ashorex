import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/data/shangan_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

/// 测试用假后端。
///
/// 按「方法 + 路径前缀」返回预置 JSON，并记录每次请求的方法、路径与请求体，
/// 让测试既能驱动页面渲染，也能对客户端实际发出的载荷做精确断言。
/// 不接触真实网络，也不启动任何服务端。
final class FakeBackend implements HttpClientAdapter {
  FakeBackend();

  final _routes = <String, _Route>{};
  final requests = <RecordedRequest>[];

  /// 注册一条路由；[status] 非 2xx 时用于验证客户端的失败分支。
  ///
  /// [bytes] 用于二进制端点（例如附件下载），与 [json] 二选一。
  void on(
    String method,
    String pathPrefix, {
    Object? json,
    List<int>? bytes,
    int status = 200,
    String? errorCode,
  }) {
    _routes['${method.toUpperCase()} $pathPrefix'] = _Route(
      json: json,
      bytes: bytes,
      status: status,
      errorCode: errorCode,
    );
  }

  /// 某个路径前缀被调用的次数，用于断言幂等与重放。
  int callCount(String method, String pathPrefix) {
    return requests
        .where(
          (request) =>
              request.method == method.toUpperCase() &&
              request.path.startsWith(pathPrefix),
        )
        .length;
  }

  /// 最近一次命中该前缀的请求；未命中时抛出，避免测试用 null 掩盖问题。
  RecordedRequest lastRequest(String method, String pathPrefix) {
    return requests.lastWhere(
      (request) =>
          request.method == method.toUpperCase() &&
          request.path.startsWith(pathPrefix),
    );
  }

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final method = options.method.toUpperCase();
    final path = options.path;
    requests.add(
      RecordedRequest(method: method, path: path, body: options.data),
    );
    final route = _match(method, path);
    if (route == null) {
      return ResponseBody.fromString(
        jsonEncode({'errorCode': 'NO_FAKE_ROUTE', 'detail': '$method $path'}),
        404,
        headers: _jsonHeaders,
      );
    }
    if (route.status >= 400) {
      return ResponseBody.fromString(
        jsonEncode({
          'errorCode': route.errorCode ?? 'FAKE_ERROR',
          'detail': '假后端按测试要求返回失败',
        }),
        route.status,
        headers: _jsonHeaders,
      );
    }
    if (route.bytes != null) {
      return ResponseBody.fromBytes(
        Uint8List.fromList(route.bytes!),
        route.status,
        headers: const {
          Headers.contentTypeHeader: ['application/octet-stream'],
        },
      );
    }
    if (route.json == null) {
      return ResponseBody.fromString('', 204, headers: _jsonHeaders);
    }
    return ResponseBody.fromString(
      jsonEncode(route.json),
      route.status,
      headers: _jsonHeaders,
    );
  }

  @override
  void close({bool force = false}) {}

  _Route? _match(String method, String path) {
    _Route? best;
    var bestLength = -1;
    _routes.forEach((key, route) {
      final parts = key.split(' ');
      if (parts[0] != method) return;
      if (!path.startsWith(parts[1])) return;
      if (parts[1].length > bestLength) {
        bestLength = parts[1].length;
        best = route;
      }
    });
    return best;
  }

  static const _jsonHeaders = {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  };
}

/// 一次被记录的请求；`body` 是客户端提交的原始载荷。
final class RecordedRequest {
  const RecordedRequest({
    required this.method,
    required this.path,
    required this.body,
  });

  final String method;
  final String path;
  final Object? body;

  /// 以 Map 形式读取请求体；非 Map 时返回空 Map。
  Map<String, Object?> get json =>
      body is Map ? Map<String, Object?>.from(body as Map) : const {};
}

final class _Route {
  const _Route({
    required this.json,
    required this.status,
    this.bytes,
    this.errorCode,
  });

  final Object? json;
  final List<int>? bytes;
  final int status;
  final String? errorCode;
}

/// 基于假后端构造真实的 [ShanganRepository]，只替换传输层。
ShanganRepository buildRepository(HttpClientAdapter backend) {
  final options = BaseOptions(baseUrl: 'https://shangan.test');
  final dio = Dio(options)..httpClientAdapter = backend;
  final refreshDio = Dio(options)..httpClientAdapter = backend;
  return ShanganRepository(
    ApiClient(dio: dio, refreshDio: refreshDio, tokenStore: MemoryTokenStore()),
  );
}

/// 内存 Token 存储，测试不接触 Keychain。
final class MemoryTokenStore implements TokenStore {
  TokenPair? _tokens = const TokenPair(
    accessToken: 'access',
    refreshToken: 'refresh',
  );

  @override
  Future<void> clear() async => _tokens = null;

  @override
  Future<TokenPair?> read() async => _tokens;

  @override
  Future<void> write(TokenPair tokens) async => _tokens = tokens;
}
