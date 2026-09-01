import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/app/application_bootstrap.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';
import 'package:shangan_ios/core/storage/token_store.dart';

void main() {
  testWidgets('切换服务端清除旧 Token 并用新地址重建应用依赖', (tester) async {
    final store = _MemoryServerConfigurationStore(
      ServerConfiguration.parse('http://127.0.0.1:18080'),
    );
    final tokenStore = _MemoryTokenStore()
      ..tokens = const TokenPair(
        accessToken: 'old-access',
        refreshToken: 'old-refresh',
      );
    ServerConfigurationController? controller;

    await tester.pumpWidget(
      ApplicationBootstrap(
        defaultBaseUrl: 'http://127.0.0.1:18080',
        configurationStore: store,
        tokenStore: tokenStore,
        configuredAppBuilder: (context, value) {
          controller = value;
          return MaterialApp(
            home: Text(
              value.configuration.baseUrl,
              textDirection: TextDirection.ltr,
            ),
          );
        },
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('http://127.0.0.1:18080'), findsOneWidget);

    await controller!.switchTo(
      ServerConfiguration.parse('https://new.example.com'),
    );
    await tester.pumpAndSettle();

    expect(find.text('https://new.example.com'), findsOneWidget);
    expect(store.saved?.baseUrl, 'https://new.example.com');
    expect(tokenStore.clearCalls, 1);
    expect(await tokenStore.read(), isNull);
  });
}

final class _MemoryServerConfigurationStore
    implements ServerConfigurationStore {
  _MemoryServerConfigurationStore(this.current);

  ServerConfiguration current;
  ServerConfiguration? saved;

  @override
  Future<ServerConfiguration> load({required String defaultBaseUrl}) async =>
      current;

  @override
  Future<void> save(ServerConfiguration configuration) async {
    current = configuration;
    saved = configuration;
  }
}

final class _MemoryTokenStore implements TokenStore {
  TokenPair? tokens;
  int clearCalls = 0;

  @override
  Future<void> clear() async {
    clearCalls += 1;
    tokens = null;
  }

  @override
  Future<TokenPair?> read() async => tokens;

  @override
  Future<void> write(TokenPair tokens) async => this.tokens = tokens;
}
