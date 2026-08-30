import 'package:flutter/material.dart';
import 'package:shangan_ios/app/application_bootstrap.dart';

/// 启动可重建的应用依赖；编译地址只作为用户未配置时的默认值。
Future<void> bootstrap() async {
  WidgetsFlutterBinding.ensureInitialized();
  const baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://127.0.0.1:8080',
  );
  runApp(const ApplicationBootstrap(defaultBaseUrl: baseUrl));
}
