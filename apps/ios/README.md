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

## 本地开发服务

服务端默认监听 `18080`。启动后，登录页右上角可打开“服务器设置”：

- iOS 模拟器使用 `http://127.0.0.1:18080`。
- 物理 iPhone 使用 Mac 的局域网地址，例如 `http://192.168.1.8:18080`。
- 生产环境使用可访问的 HTTPS 地址。

App 会先请求 `/actuator/health`，只有服务状态为 `UP` 才保存新地址。切换服务端会清除旧服务器登录 Token，并重新创建全部网络依赖。

本地服务端地址可通过构建参数覆盖：

```bash
fvm flutter run --dart-define=API_BASE_URL=https://example.test
```

Access Token 与 Refresh Token 仅保存到 iOS Keychain；页面不得直接调用 Dio。
