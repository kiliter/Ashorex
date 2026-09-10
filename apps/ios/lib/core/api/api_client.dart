import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

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
  static const _requestRefreshMarker = 'shangan.request_refresh';

  final Dio _dio;
  final Dio _refreshDio;
  final TokenStore _tokenStore;
  Future<TokenPair>? _refreshFuture;
  String? _refreshOwner;
  String? _lastRefreshSource;
  TokenPair? _lastRefreshResult;

  /// 更新下载只解析本站固定路径，拒绝绝对地址或跨源重定向。
  Uri appUpdateUri(String path) {
    if (!RegExp(
      r'^/(api/v1/app-updates/releases/v[0-9]+\.[0-9]+\.[0-9]+/assets/(android|ios)|downloads/app/v[0-9]+\.[0-9]+\.[0-9]+/ios)$',
    ).hasMatch(path)) {
      throw const FormatException('更新下载地址无效');
    }
    return Uri.parse(_dio.options.baseUrl).resolve(path);
  }

  /// 原生播放器不经过 Dio：先验证会话触发必要刷新，再提供固定同源流地址和请求头。
  Future<({Uri uri, Map<String, String> headers})> playbackSource(
    String resourceId,
  ) async {
    await getJson('/api/v1/me');
    final tokens = await _tokenStore.read();
    if (tokens == null) throw StateError('请重新登录');
    return (
      uri: Uri.parse(
        _dio.options.baseUrl,
      ).resolve('/api/v1/playback/${Uri.encodeComponent(resourceId)}/stream'),
      headers: {'Authorization': 'Bearer ${tokens.accessToken}'},
    );
  }

  /// Token 彻底失效时由应用层接管导航；回调不得抛出异常。
  Future<void> Function()? onAuthenticationLost;

  /// 订阅 SSE 数据帧，复用 Bearer 刷新；取消订阅也取消尚在连接中的 HTTP 请求。
  Stream<String> eventStream(String path) {
    final cancel = CancelToken();
    StreamSubscription<String>? subscription;
    late final StreamController<String> controller;
    controller = StreamController<String>(
      onListen: () async {
        try {
          final response = await _dio.get<ResponseBody>(
            path,
            cancelToken: cancel,
            options: Options(
              responseType: ResponseType.stream,
              receiveTimeout: const Duration(seconds: 45),
              headers: {'Accept': 'text/event-stream'},
            ),
          );
          if (cancel.isCancelled) return;
          final body = response.data;
          if (body == null ||
              !(response.headers.value('content-type') ?? '').startsWith(
                'text/event-stream',
              )) {
            throw const FormatException('催办通知流响应格式错误');
          }
          // UTF-8 解码与按行拆分均保留跨网络分片状态；仅在空行时派发完整 data 帧。
          final data = <String>[];
          subscription = body.stream
              .cast<List<int>>()
              .transform(utf8.decoder)
              .transform(const LineSplitter())
              .listen(
                (line) {
                  if (line.isEmpty) {
                    if (data.isNotEmpty) controller.add(data.join('\n'));
                    data.clear();
                  } else if (line.startsWith('data:')) {
                    final value = line.substring(5);
                    data.add(
                      value.startsWith(' ') ? value.substring(1) : value,
                    );
                  }
                },
                onError: controller.addError,
                onDone: () {
                  // 上游已结束时先解除引用，避免关闭外层流反向等待自身取消而形成死锁。
                  subscription = null;
                  unawaited(controller.close());
                },
              );
        } catch (error, stack) {
          if (!cancel.isCancelled) {
            controller.addError(error, stack);
            await controller.close();
          }
        }
      },
      onCancel: () async {
        cancel.cancel('催办订阅已结束');
        await subscription?.cancel();
      },
    );
    return controller.stream;
  }

  /// 公共发布信息不依赖登录态，避免旧 token 影响检查更新或触发退出登录。
  Future<Map<String, dynamic>> getPublicJson(String path) async {
    try {
      final response = await _dio.get<dynamic>(
        path,
        options: Options(extra: {_skipAuthMarker: true}),
      );
      return _asJson(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<Map<String, dynamic>> getJson(String path) async {
    try {
      final response = await _dio.get<dynamic>(path);
      return _asJson(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// 读取允许以 204 表示资源尚未创建的单资源接口。
  Future<Map<String, dynamic>?> getOptionalJson(String path) async {
    try {
      final response = await _dio.get<dynamic>(path);
      if (response.statusCode == 204 || response.data == null) {
        return null;
      }
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

  /// PATCH 并读取 JSON 响应。
  Future<Map<String, dynamic>> patchJson(String path, {Object? data}) async {
    try {
      final response = await _dio.patch<dynamic>(path, data: data);
      return _asJson(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// PATCH 且不关心响应体（例如改密码返回 204）。
  Future<void> patchEmpty(String path, {Object? data}) async {
    try {
      await _dio.patch<void>(path, data: data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// 带请求体的 DELETE；删除 Todo 必须携带原因，因此不能用无体删除。
  Future<void> deleteWithBody(String path, {Object? data}) async {
    try {
      await _dio.delete<void>(path, data: data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// 上传单个二进制文件并读取 JSON 响应，业务页面不直接操作 Dio。
  ///
  /// [contentType] 必须显式传入：Dio 默认按 `application/octet-stream` 发送，
  /// 而服务端只接受图片与 PDF，会直接判为 `ATTACHMENT_TYPE_UNSUPPORTED`。
  Future<Map<String, dynamic>> postFile(
    String path, {
    required String fieldName,
    required String filename,
    required String contentType,
    required List<int> bytes,
  }) async {
    try {
      final response = await _dio.post<dynamic>(
        path,
        data: FormData.fromMap({
          fieldName: MultipartFile.fromBytes(
            bytes,
            filename: filename,
            contentType: DioMediaType.parse(contentType),
          ),
        }),
      );
      return _asJson(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// 读取二进制响应，仍然经过 Bearer 拦截器。
  ///
  /// 附件下载端点要求 Authorization 头，`Image.network` 不会携带，
  /// 因此页面只能先拿到字节再用 `Image.memory` 渲染。
  Future<Uint8List> getBytes(String path) async {
    try {
      final response = await _dio.get<List<int>>(
        path,
        options: Options(responseType: ResponseType.bytes),
      );
      return Uint8List.fromList(response.data ?? const <int>[]);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> deleteEmpty(String path) async {
    try {
      await _dio.delete<void>(path);
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

  /// 服务端切换或 App 销毁时关闭连接池，避免旧 Origin 继续持有活动连接。
  void close() {
    _dio.close(force: true);
    _refreshDio.close(force: true);
  }

  Future<void> _onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (options.extra[_skipAuthMarker] != true) {
      final tokens = await _tokenStore.read();
      // 重试仍属于原会话；异步取 Token 期间换账号时不能把旧请求交给新账号执行。
      final expected = options.extra[_requestRefreshMarker] as String?;
      if (expected != null && tokens?.refreshToken != expected) {
        handler.reject(
          DioException(
            requestOptions: options,
            type: DioExceptionType.cancel,
            message: '登录会话已切换',
          ),
        );
        return;
      }
      if (tokens != null) {
        options.extra[_requestRefreshMarker] = tokens.refreshToken;
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

    final expected = request.extra[_requestRefreshMarker] as String?;
    final current = await _tokenStore.read();
    if (expected == null || current == null) {
      handler.next(error);
      return;
    }
    // 同一次正常轮换后的并发旧 401 可以使用新 Token；换账号则结束旧请求。
    final alreadyRefreshed =
        _lastRefreshSource == expected &&
        _lastRefreshResult?.refreshToken == current.refreshToken;
    if (current.refreshToken != expected && !alreadyRefreshed) {
      handler.next(
        DioException(
          requestOptions: request,
          type: DioExceptionType.cancel,
          message: '登录会话已切换',
        ),
      );
      return;
    }
    if (request.extra[_retryMarker] == true) {
      await _expireSession(expected);
      handler.next(error);
      return;
    }

    try {
      final tokens = alreadyRefreshed ? current : await _refreshOnce(expected);
      request.extra[_retryMarker] = true;
      request.extra[_requestRefreshMarker] = tokens.refreshToken;
      request.headers['Authorization'] = 'Bearer ${tokens.accessToken}';
      final response = await _dio.fetch<dynamic>(request);
      handler.resolve(response);
    } catch (refreshError) {
      // 只结束发起失败刷新请求的那次会话，迟到的旧失败不得清除新登录凭据。
      if (_isDefinitiveRefreshRejection(refreshError)) {
        if ((await _tokenStore.read())?.refreshToken != expected) {
          handler.next(
            DioException(
              requestOptions: request,
              type: DioExceptionType.cancel,
              message: '登录会话已切换',
            ),
          );
          return;
        }
        await _expireSession(expected);
        handler.next(error);
      } else if (refreshError is DioException) {
        handler.next(refreshError);
      } else if (refreshError is ApiException &&
          refreshError.errorCode == 'AUTH_SESSION_CHANGED') {
        handler.next(
          DioException(
            requestOptions: request,
            type: DioExceptionType.cancel,
            message: '登录会话已切换',
          ),
        );
      } else {
        handler.next(error);
      }
    }
  }

  bool _isDefinitiveRefreshRejection(Object error) {
    if (error is! DioException) return false;
    final status = error.response?.statusCode;
    return status == 400 || status == 401 || status == 403;
  }

  /// 并发 401 共享同一个 Future，确保 Refresh Token 只轮换一次。
  Future<TokenPair> _refreshOnce(String expected) async {
    final inFlight = _refreshFuture;
    if (inFlight != null) {
      if (_refreshOwner != expected) throw _sessionChanged();
      return inFlight;
    }
    _refreshOwner = expected;
    final operation = _performRefresh(expected);
    _refreshFuture = operation;
    try {
      return await operation;
    } finally {
      if (identical(_refreshFuture, operation)) {
        _refreshFuture = null;
        _refreshOwner = null;
      }
    }
  }

  Future<TokenPair> _performRefresh(String expected) async {
    final current = await _tokenStore.read();
    if (current == null || current.refreshToken != expected) {
      throw _sessionChanged();
    }
    final response = await _refreshDio.post<dynamic>(
      '/api/v1/auth/refresh',
      data: {'refreshToken': current.refreshToken},
    );
    final tokens = TokenPair.fromJson(_asJson(response.data));
    // 网络等待后再次核对会话，注销或换账号后的旧响应不能写回 Keychain。
    if ((await _tokenStore.read())?.refreshToken != expected) {
      throw _sessionChanged();
    }
    await _tokenStore.write(tokens);
    _lastRefreshSource = expected;
    _lastRefreshResult = tokens;
    return tokens;
  }

  Future<void> _expireSession(String expected) async {
    if ((await _tokenStore.read())?.refreshToken != expected) return;
    final callback = onAuthenticationLost;
    if (callback != null) {
      await callback();
    } else {
      await _tokenStore.clear();
    }
  }

  /// 会话切换不是刷新凭据无效，不触发清理新会话。
  ApiException _sessionChanged() => const ApiException(
    statusCode: null,
    errorCode: 'AUTH_SESSION_CHANGED',
    message: '登录会话已切换',
  );

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
