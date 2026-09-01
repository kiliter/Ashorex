# 上岸

上岸 V1 是一款面向 iPhone、iPad 和 Android 的 Flutter 学习监督 App，围绕考试目标、每日计划、可信学习、学习欠债和报表构建完整学习闭环。

## 常用命令

- `./run.sh`：自动选择 Java 21，启动后端、iPhone 模拟器和 Flutter 调试会话。
- `./run.sh server` / `./run.sh ios`：只启动服务端或 iOS 调试会话。
- `./test.sh`：执行服务端逻辑测试和 Flutter 完整验证。
- `./test.sh server` / `./test.sh ios`：只验证指定模块。
- `make server-test`：运行服务端完整验证。
- `make ios-test`：解析 Flutter 依赖，并执行格式、静态分析和测试。
- `make format`：格式化服务端和 iOS 代码。
- `make verify`：依次执行服务端与 iOS 完整验证。

## Java 版本切换

仓库根目录的 `.sdkmanrc` 固定使用 SDKMAN 候选版本 `21.0.7.fx-zulu`。本机开启
`sdkman_auto_env=true` 后，进入项目目录会自动切换到 Java 21，不会修改 SDKMAN 的全局默认版本。

自动切换未生效时，可以在已初始化 SDKMAN 的终端中手动执行：

```bash
sdk env
java -version
cd apps/server && ./mvnw --version
```

如果本机尚未安装配置中的 Java 版本，执行 `sdk env install` 安装。

修改代码前，必须依次阅读 `AGENTS.md`、V1 设计规范、实施计划和需求追踪矩阵。

## V1 边界

- 客户端使用同一 Flutter 工程支持 iPhone、iPad 和 Android，最低支持 iOS 16 和 Android API 24。
- GitHub Actions 会上传无签名 IPA、Android Debug APK 和可通过 `docker load` 导入的服务端镜像；`main` 分支同时推送服务端镜像到 GHCR。
- 服务端使用 Java 21、Spring Boot 模块化单体和本机 SQLite WAL。
- Emby 凭据仅保存在服务端，课程全文和摘要由管理员通过 ZIP 批量导入。
- 服务端不运行 LLM、ASR、MCP、AI 对话或自动转写任务。
- 不实现 PC 学习 Web、桌面客户端、微服务、Redis、Kafka、向量数据库或多智能体。
- 服务端自动化测试只保留不连接真实数据库的逻辑、Controller 切片和外部协议测试。
