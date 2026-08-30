import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

/// 管理当前服务端地址；切换时先隔离旧凭据，再通知根组件重建网络依赖。
final class ServerConfigurationController extends ChangeNotifier {
  factory ServerConfigurationController({
    required ServerConfiguration initialConfiguration,
    required ServerConfigurationStore store,
    required TokenStore tokenStore,
  }) =>
      ServerConfigurationController._(initialConfiguration, store, tokenStore);

  ServerConfigurationController._(
    this._configuration,
    this._store,
    this._tokenStore,
  );

  final ServerConfigurationStore _store;
  final TokenStore _tokenStore;
  ServerConfiguration _configuration;
  bool _switching = false;

  ServerConfiguration get configuration => _configuration;
  bool get switching => _switching;

  Future<void> switchTo(ServerConfiguration next) async {
    if (_configuration == next) return;
    if (_switching) {
      throw StateError('服务端正在切换，请稍候');
    }

    final previous = _configuration;
    _switching = true;
    notifyListeners();
    try {
      await _store.save(next);
      try {
        // 服务器身份边界发生变化，旧 Token 绝不能被发送到新服务器。
        await _tokenStore.clear();
      } catch (_) {
        await _store.save(previous);
        rethrow;
      }
      _configuration = next;
    } finally {
      _switching = false;
      notifyListeners();
    }
  }
}

/// 生产环境由 ApplicationBootstrap 注入，Widget 测试可替换为内存实现。
final serverConfigurationControllerProvider =
    Provider<ServerConfigurationController>((ref) {
      throw StateError('ServerConfigurationController 尚未注入');
    });
