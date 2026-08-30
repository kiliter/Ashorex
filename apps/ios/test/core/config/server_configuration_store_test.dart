import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';
import 'package:shangan_ios/core/config/server_configuration_store.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('用户保存地址优先于编译默认地址', () async {
    SharedPreferences.setMockInitialValues({
      SharedPreferencesServerConfigurationStore.preferenceKey:
          'https://saved.example.com',
    });
    final store = await SharedPreferencesServerConfigurationStore.create();

    final configuration = await store.load(
      defaultBaseUrl: 'http://127.0.0.1:8080',
    );

    expect(configuration.baseUrl, 'https://saved.example.com');
  });

  test('没有用户地址时使用编译默认地址，并能保存规范化地址', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await SharedPreferencesServerConfigurationStore.create();

    expect(
      (await store.load(defaultBaseUrl: 'http://127.0.0.1:8080')).baseUrl,
      'http://127.0.0.1:8080',
    );

    await store.save(ServerConfiguration.parse('https://next.example.com/'));
    expect(
      (await store.load(defaultBaseUrl: 'http://127.0.0.1:8080')).baseUrl,
      'https://next.example.com',
    );
  });
}
