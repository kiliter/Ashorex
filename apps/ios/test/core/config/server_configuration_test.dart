import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/config/server_configuration.dart';

void main() {
  group('ServerConfiguration', () {
    test('接受 HTTP/HTTPS Origin 并移除末尾斜杠', () {
      expect(
        ServerConfiguration.parse('http://127.0.0.1:18080/').baseUrl,
        'http://127.0.0.1:18080',
      );
      expect(
        ServerConfiguration.parse('https://study.example.com').baseUrl,
        'https://study.example.com',
      );
    });

    test('拒绝不安全或不完整的服务端地址', () {
      const invalidValues = [
        '',
        'study.example.com',
        'ftp://study.example.com',
        'https://',
        'https://user:password@study.example.com',
        'https://study.example.com/api',
        'https://study.example.com?token=value',
        'https://study.example.com#fragment',
      ];

      for (final value in invalidValues) {
        expect(
          () => ServerConfiguration.parse(value),
          throwsA(isA<FormatException>()),
          reason: '应拒绝：$value',
        );
      }
    });

    test('展示当前服务端主机和端口', () {
      expect(
        ServerConfiguration.parse('http://192.168.1.8:18080').displayLabel,
        '192.168.1.8:18080',
      );
      expect(
        ServerConfiguration.parse('https://study.example.com').displayLabel,
        'study.example.com',
      );
    });
  });
}
