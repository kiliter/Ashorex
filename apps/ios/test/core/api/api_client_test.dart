import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

void main() {
  for (final status in [200, 401]) {
    test('旧账号刷新响应 $status 迟到不覆盖或清除新账号 Token', () async {
      final store = _MemoryTokenStore(
        const TokenPair(accessToken: 'a-access', refreshToken: 'a-refresh'),
      );
      final adapter = _DelayedAuthAdapter();
      final client = _client(adapter, store);
      var lost = 0;
      client.onAuthenticationLost = () async {
        lost++;
        await store.clear();
      };
      final rejected = expectLater(
        client.getJson('/api/v1/me'),
        throwsA(isA<ApiException>()),
      );
      await adapter.refreshStarted.future;
      await store.write(
        const TokenPair(accessToken: 'b-access', refreshToken: 'b-refresh'),
      );
      adapter.refreshResponse.complete(
        _response(
          status,
          status == 200
              ? {'accessToken': 'a-new-access', 'refreshToken': 'a-new-refresh'}
              : {'errorCode': 'AUTH_INVALID_REFRESH_TOKEN'},
        ),
      );
      await rejected;
      expect((await store.read())!.accessToken, 'b-access');
      expect((await store.read())!.refreshToken, 'b-refresh');
      expect(lost, 0);
      expect(adapter.meCalls, 1);
      client.close();
    });
  }

  test('旧请求 401 晚于新账号登录时不刷新新账号也不重试旧请求', () async {
    final store = _MemoryTokenStore(
      const TokenPair(accessToken: 'a-access', refreshToken: 'a-refresh'),
    );
    final adapter = _DelayedAuthAdapter(delayUnauthorized: true);
    final client = _client(adapter, store);
    final rejected = expectLater(
      client.getJson('/api/v1/me'),
      throwsA(isA<ApiException>()),
    );
    await adapter.meStarted.future;
    await store.write(
      const TokenPair(accessToken: 'b-access', refreshToken: 'b-refresh'),
    );
    adapter.unauthorized.complete();
    await rejected;
    expect(adapter.refreshCalls, 0);
    expect(adapter.meCalls, 1);
    expect((await store.read())!.refreshToken, 'b-refresh');
    client.close();
  });

  test('注销后旧刷新成功也不能恢复登录 Token', () async {
    final store = _MemoryTokenStore(
      const TokenPair(accessToken: 'a-access', refreshToken: 'a-refresh'),
    );
    final adapter = _DelayedAuthAdapter();
    final client = _client(adapter, store);
    final rejected = expectLater(
      client.getJson('/api/v1/me'),
      throwsA(isA<ApiException>()),
    );
    await adapter.refreshStarted.future;
    await store.clear();
    adapter.refreshResponse.complete(
      _response(200, {
        'accessToken': 'a-new-access',
        'refreshToken': 'a-new-refresh',
      }),
    );
    await rejected;
    expect(await store.read(), isNull);
    expect(adapter.meCalls, 1);
    client.close();
  });

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

/// 使用可控网络响应重现跨登录会话竞态，不使用真实 HTTP 或任意延时。
ApiClient _client(HttpClientAdapter adapter, TokenStore store) => ApiClient(
  dio: Dio(BaseOptions(baseUrl: 'https://api.example.test'))
    ..httpClientAdapter = adapter,
  refreshDio: Dio(BaseOptions(baseUrl: 'https://api.example.test'))
    ..httpClientAdapter = adapter,
  tokenStore: store,
);

ResponseBody _response(int status, Map<String, Object> body) =>
    ResponseBody.fromString(
      jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );

final class _DelayedAuthAdapter implements HttpClientAdapter {
  _DelayedAuthAdapter({this.delayUnauthorized = false});
  final bool delayUnauthorized;
  final meStarted = Completer<void>();
  final unauthorized = Completer<void>();
  final refreshStarted = Completer<void>();
  final refreshResponse = Completer<ResponseBody>();
  int meCalls = 0;
  int refreshCalls = 0;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path.endsWith('/refresh')) {
      refreshCalls++;
      refreshStarted.complete();
      return refreshResponse.future;
    }
    meCalls++;
    if (!meStarted.isCompleted) meStarted.complete();
    if (delayUnauthorized) await unauthorized.future;
    return _response(401, {'errorCode': 'AUTH_ACCESS_TOKEN_INVALID'});
  }

  @override
  void close({bool force = false}) {}
}
