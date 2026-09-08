import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shangan_ios/core/storage/remembered_login_store.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';

/// 使用插件内存通道验证持久化边界，不写真实设备凭据。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
  });
  test('账号密码按服务器隔离，重建后恢复，取消只删除当前服务器', () async {
    final store = RememberedLoginStore();
    await store.write(
      'http://localhost:18080',
      const RememberedLogin('dev', 'dev-password'),
    );
    await store.write(
      'https://example.com',
      const RememberedLogin('prod', 'prod-password'),
    );
    final restored = RememberedLoginStore();
    expect((await restored.read('http://localhost:18080'))?.username, 'dev');
    expect(
      (await restored.read('https://example.com'))?.password,
      'prod-password',
    );
    await restored.write('http://localhost:18080', null);
    expect(await store.read('http://localhost:18080'), isNull);
    expect((await store.read('https://example.com'))?.username, 'prod');
  });
  test('服务器历史去重置顶并跨实例保留', () async {
    final store = ServerHistoryStore();
    await store.remember(ServerConfiguration.parse('http://localhost:18080'));
    await store.remember(ServerConfiguration.parse('https://example.com/'));
    await store.remember(ServerConfiguration.parse('http://localhost:18080/'));
    expect(await ServerHistoryStore().read(), [
      'http://localhost:18080',
      'https://example.com',
    ]);
    await store.remove('https://example.com');
    expect(await ServerHistoryStore().read(), ['http://localhost:18080']);
  });
}
