import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

void main() {
  test('并发 401 只轮换一次 Refresh Token 并分别重试', () async {
    final tokenStore = _MemoryTokenStore(
      const TokenPair(accessToken: 'expired-access', refreshToken: 'refresh-1'),
    );
    final adapter = _AuthQueueAdapter(expectedUnauthorizedRequests: 2);
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test'))
      ..httpClientAdapter = adapter;
    final refreshDio = Dio(BaseOptions(baseUrl: 'https://api.example.test'))
      ..httpClientAdapter = adapter;
    final client = ApiClient(
      dio: dio,
      refreshDio: refreshDio,
      tokenStore: tokenStore,
    );

    final users = await Future.wait([
      client.getJson('/api/v1/me'),
      client.getJson('/api/v1/me'),
    ]);

    expect(users.map((user) => user['id']), everyElement('user-1'));
    expect(adapter.refreshCalls, 1);
    expect(adapter.meCalls, 4);
    expect((await tokenStore.read())!.refreshToken, 'refresh-2');
  });
}

/// 仅用于测试的内存 Token 存储，不接触真实 Keychain。
final class _MemoryTokenStore implements TokenStore {
  _MemoryTokenStore(this._tokens);

  TokenPair? _tokens;

  @override
  Future<void> clear() async => _tokens = null;

  @override
  Future<TokenPair?> read() async => _tokens;

  @override
  Future<void> write(TokenPair tokens) async => _tokens = tokens;
}

/// 模拟两个请求同时 401，Refresh 成功后新 Token 请求全部成功。
final class _AuthQueueAdapter implements HttpClientAdapter {
  _AuthQueueAdapter({required this.expectedUnauthorizedRequests});

  final int expectedUnauthorizedRequests;
  final Completer<void> _allUnauthorized = Completer<void>();
  int meCalls = 0;
  int refreshCalls = 0;
  int _unauthorizedCalls = 0;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path.endsWith('/api/v1/auth/refresh')) {
      refreshCalls += 1;
      await _allUnauthorized.future;
      return _jsonResponse(200, {
        'accessToken': 'new-access',
        'refreshToken': 'refresh-2',
      });
    }
    if (options.path.endsWith('/api/v1/me')) {
      meCalls += 1;
      if (options.headers['Authorization'] != 'Bearer new-access') {
        _unauthorizedCalls += 1;
        if (_unauthorizedCalls == expectedUnauthorizedRequests &&
            !_allUnauthorized.isCompleted) {
          _allUnauthorized.complete();
        }
        return _jsonResponse(401, {
          'errorCode': 'AUTH_ACCESS_TOKEN_INVALID',
          'detail': 'Access Token 无效',
        });
      }
      return _jsonResponse(200, {
        'id': 'user-1',
        'username': 'alice',
        'displayName': 'Alice',
        'role': 'USER',
        'timezone': 'Asia/Shanghai',
      });
    }
    return _jsonResponse(404, {'errorCode': 'NOT_FOUND'});
  }

  ResponseBody _jsonResponse(int statusCode, Map<String, dynamic> body) {
    return ResponseBody.fromString(
      jsonEncode(body),
      statusCode,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
