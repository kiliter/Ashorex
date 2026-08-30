# 上岸 iOS 客户端

本目录是“上岸”V1 的 Flutter iOS 应用，仅生成 iOS 平台工程，最低支持 iOS 16。

## 本地开发

项目使用 FVM 固定 Flutter 3.44.7。常用命令：

```bash
fvm flutter pub get
fvm flutter analyze
fvm flutter test
fvm flutter build ios --simulator --no-codesign
```

本地服务端地址可通过构建参数覆盖：

```bash
fvm flutter run --dart-define=API_BASE_URL=https://example.test
```

Access Token 与 Refresh Token 仅保存到 iOS Keychain；页面不得直接调用 Dio。
