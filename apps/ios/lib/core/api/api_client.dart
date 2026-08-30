import 'dart:async';

import 'package:dio/dio.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

/// 统一的 Dio 客户端，负责 Bearer Token、单飞刷新和一次性请求重试。
final class ApiClient {
  factory ApiClient({
    required Dio dio,
    required Dio refreshDio,
    required TokenStore tokenStore,
  }) {
    return ApiClient._(dio, refreshDio, tokenStore);
  }

  ApiClient._(this._dio, this._refreshDio, this._tokenStore) {
    _dio.interceptors.add(
      InterceptorsWrapper(onRequest: _onRequest, onError: _onError),
    );
  }

  factory ApiClient.create({
    required String baseUrl,
    required TokenStore tokenStore,
  }) {
    final options = BaseOptions(
      baseUrl: baseUrl,
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
      contentType: Headers.jsonContentType,
    );
    return ApiClient(
      dio: Dio(options),
      refreshDio: Dio(options),
      tokenStore: tokenStore,
    );
  }

  static const _retryMarker = 'shangan.auth_retried';
  static const _skipAuthMarker = 'shangan.skip_auth';

  final Dio _dio;
  final Dio _refreshDio;
  final TokenStore _tokenStore;
  Future<TokenPair>? _refreshFuture;

  /// Token 彻底失效时由应用层接管导航；回调不得抛出异常。
  Future<void> Function()? onAuthenticationLost;

  Future<Map<String, dynamic>> getJson(String path) async {
    try {
      final response = await _dio.get<dynamic>(path);
      return _asJson(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// 读取直接数组响应，列表接口不得额外套无意义包装对象。
  Future<List<Map<String, dynamic>>> getJsonList(String path) async {
    try {
      final response = await _dio.get<dynamic>(path);
      final data = response.data;
      if (data is List) {
        return data
            .map((item) => Map<String, dynamic>.from(item as Map))
            .toList();
      }
      throw const ApiException(
        statusCode: null,
        errorCode: 'INVALID_RESPONSE',
        message: '服务端响应格式不正确',
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<Map<String, dynamic>> postJson(
    String path, {
    Object? data,
    bool skipAuthentication = false,
  }) async {
    try {
      final response = await _dio.post<dynamic>(
        path,
        data: data,
        options: Options(extra: {_skipAuthMarker: skipAuthentication}),
      );
      return _asJson(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<Map<String, dynamic>> putJson(String path, {Object? data}) async {
    try {
      final response = await _dio.put<dynamic>(path, data: data);
      return _asJson(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> postEmpty(
    String path, {
    Object? data,
    bool skipAuthentication = false,
  }) async {
    try {
      await _dio.post<void>(
        path,
        data: data,
        options: Options(extra: {_skipAuthMarker: skipAuthentication}),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> _onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (options.extra[_skipAuthMarker] != true) {
      final tokens = await _tokenStore.read();
      if (tokens != null) {
        options.headers['Authorization'] = 'Bearer ${tokens.accessToken}';
      }
    }
    handler.next(options);
  }

  Future<void> _onError(
    DioException error,
    ErrorInterceptorHandler handler,
  ) async {
    final request = error.requestOptions;
    if (error.response?.statusCode != 401 ||
        request.extra[_skipAuthMarker] == true ||
        request.path.startsWith('/api/v1/auth/')) {
      handler.next(error);
      return;
    }

    if (request.extra[_retryMarker] == true) {
      await _expireSession();
      handler.next(error);
      return;
    }

    try {
      final tokens = await _refreshOnce();
      request.extra[_retryMarker] = true;
      request.headers['Authorization'] = 'Bearer ${tokens.accessToken}';
      final response = await _dio.fetch<dynamic>(request);
      handler.resolve(response);
    } catch (_) {
      await _expireSession();
      handler.next(error);
    }
  }

  /// 并发 401 共享同一个 Future，确保 Refresh Token 只轮换一次。
  Future<TokenPair> _refreshOnce() async {
    final inFlight = _refreshFuture;
    if (inFlight != null) {
      return inFlight;
    }
    final operation = _performRefresh();
    _refreshFuture = operation;
    try {
      return await operation;
    } finally {
      if (identical(_refreshFuture, operation)) {
        _refreshFuture = null;
      }
    }
  }

  Future<TokenPair> _performRefresh() async {
    final current = await _tokenStore.read();
    if (current == null) {
      throw const ApiException(
        statusCode: 401,
        errorCode: 'AUTH_REFRESH_TOKEN_MISSING',
        message: '登录状态已失效',
      );
    }
    final response = await _refreshDio.post<dynamic>(
      '/api/v1/auth/refresh',
      data: {'refreshToken': current.refreshToken},
    );
    final tokens = TokenPair.fromJson(_asJson(response.data));
    await _tokenStore.write(tokens);
    return tokens;
  }

  Future<void> _expireSession() async {
    await _tokenStore.clear();
    await onAuthenticationLost?.call();
  }

  static Map<String, dynamic> _asJson(dynamic data) {
    if (data is Map) {
      return Map<String, dynamic>.from(data);
    }
    throw const ApiException(
      statusCode: null,
      errorCode: 'INVALID_RESPONSE',
      message: '服务端响应格式不正确',
    );
  }
}
