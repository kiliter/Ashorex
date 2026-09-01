# 上岸 Flutter 移动客户端

本目录沿用历史路径 `apps/ios`，实际是“上岸”V1 的 Flutter 移动应用，支持 iPhone、iPad 和 Android。最低支持 iOS 16 与 Android API 24。

## 本地开发

项目使用 FVM 固定 Flutter 3.44.7。常用命令：

```bash
fvm flutter pub get
fvm flutter analyze
fvm flutter test
fvm flutter build ios --simulator --no-codesign
fvm flutter build apk --debug
```

## 本地开发服务

服务端默认监听 `18080`。启动后，登录页右上角可打开“服务器设置”：

- iOS/iPadOS 模拟器使用 `http://127.0.0.1:18080`。
- 物理 iPhone、iPad 和 Android 使用 Mac 的局域网地址，例如 `http://192.168.1.8:18080`。
- 生产环境使用可访问的 HTTPS 地址。

App 会先请求 `/actuator/health`，只有服务状态为 `UP` 才保存新地址。切换服务端会清除旧服务器登录 Token，并重新创建全部网络依赖。

本地服务端地址可通过构建参数覆盖：

```bash
fvm flutter run --dart-define=API_BASE_URL=https://example.test
```

Access Token 与 Refresh Token 通过 `flutter_secure_storage` 保存到 iOS Keychain 或 Android 加密存储；页面不得直接调用 Dio。
