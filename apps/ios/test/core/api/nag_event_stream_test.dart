import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/api/api_client.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

/// 在 Dio 适配器边界验证 SSE 分片和取消，认证仍经过真实拦截器。
void main() {
  test('中文跨字节分片、CRLF、多行 data 和注释按完整帧解析', () async {
    final adapter = _StreamAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test'))
      ..httpClientAdapter = adapter;
    final client = ApiClient(dio: dio, refreshDio: dio, tokenStore: _Tokens());
    final result = client.eventStream('/api/v1/nags/events').toList();
    await adapter.connected.future;
    for (final byte in utf8.encode(
      ': ping\r\nevent: nag\r\ndata: 催办\r\ndata: 第二行\r\n\r\ndata: 未完成',
    )) {
      adapter.body.add(Uint8List.fromList([byte]));
    }
    await adapter.body.close();
    expect(await result, ['催办\n第二行']);
    expect(adapter.authorization, 'Bearer test-access');
    client.close();
  });

  test('SSE 建连遇到 401 时刷新令牌后重新订阅', () async {
    final adapter = _StreamAdapter(refreshRequired: true);
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test'))
      ..httpClientAdapter = adapter;
    final refreshDio = Dio(BaseOptions(baseUrl: 'https://example.test'))
      ..httpClientAdapter = adapter;
    final client = ApiClient(
      dio: dio,
      refreshDio: refreshDio,
      tokenStore: _Tokens(),
    );
    final result = client.eventStream('/api/v1/nags/events').toList();
    await adapter.connected.future;
    adapter.body.add(Uint8List.fromList(utf8.encode('data: ready\n\n')));
    await adapter.body.close();
    expect(await result, ['ready']);
    expect(adapter.authorization, 'Bearer renewed-access');
    expect(adapter.refreshes, 1);
    client.close();
  });

  testWidgets('连接无数据超过 45 秒时报告超时而不是永久挂起', (tester) async {
    final adapter = _StreamAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test'))
      ..httpClientAdapter = adapter;
    final client = ApiClient(dio: dio, refreshDio: dio, tokenStore: _Tokens());
    final errors = <Object>[];
    final subscription = client
        .eventStream('/api/v1/nags/events')
        .listen((_) {}, onError: errors.add);
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 46));
    await tester.pumpAndSettle();
    expect(errors, hasLength(1));
    expect(
      (errors.single as DioException).type,
      DioExceptionType.receiveTimeout,
    );
    var cancelled = false;
    unawaited(subscription.cancel().then((_) => cancelled = true));
    await tester.pump();
    expect(cancelled, isTrue);
    unawaited(adapter.body.close());
    client.close();
  });

  test('取消流会取消 HTTP 连接', () async {
    final adapter = _StreamAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test'))
      ..httpClientAdapter = adapter;
    final client = ApiClient(dio: dio, refreshDio: dio, tokenStore: _Tokens());
    final subscription = client
        .eventStream('/api/v1/nags/events')
        .listen((_) {});
    await adapter.connected.future;
    await subscription.cancel();
    await adapter.cancelled.future;
    expect(adapter.cancelled.isCompleted, isTrue);
    await adapter.body.close();
    client.close();
  });
}

final class _StreamAdapter implements HttpClientAdapter {
  _StreamAdapter({this.refreshRequired = false});
  final bool refreshRequired;
  int refreshes = 0;
  final body = StreamController<Uint8List>();
  final connected = Completer<void>();
  final cancelled = Completer<void>();
  String? authorization;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path.endsWith('/refresh')) {
      refreshes++;
      return ResponseBody.fromString(
        jsonEncode({
          'accessToken': 'renewed-access',
          'refreshToken': 'renewed-refresh',
        }),
        200,
        headers: {
          'content-type': ['application/json'],
        },
      );
    }
    authorization = options.headers['Authorization'] as String?;
    if (refreshRequired && authorization != 'Bearer renewed-access') {
      return ResponseBody.fromString(
        '{}',
        401,
        headers: {
          'content-type': ['application/json'],
        },
      );
    }
    cancelFuture?.then((_) {
      if (!cancelled.isCompleted) cancelled.complete();
    });
    connected.complete();
    return ResponseBody(
      body.stream,
      200,
      headers: {
        'content-type': ['text/event-stream'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

/// 测试凭据仅驻内存，不访问系统 Keychain。
final class _Tokens implements TokenStore {
  TokenPair? _tokens = const TokenPair(
    accessToken: 'test-access',
    refreshToken: 'test-refresh',
  );
  @override
  Future<TokenPair?> read() async => _tokens;
  @override
  Future<void> clear() async {
    _tokens = null;
  }

  @override
  Future<void> write(TokenPair tokens) async {
    _tokens = tokens;
  }
}
