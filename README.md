# 上岸

上岸 V1 是一款仅面向 iOS 的学习监督 App，围绕考试目标、每日计划、可信学习、学习欠债、报表和只读 AI 构建完整学习闭环。

## 常用命令

- `make server-test`：运行服务端完整验证。
- `make ios-test`：解析 Flutter 依赖，并执行格式、静态分析和测试。
- `make format`：格式化服务端和 iOS 代码。
- `make verify`：依次执行服务端与 iOS 完整验证。

修改代码前，必须依次阅读 `AGENTS.md`、V1 设计规范、实施计划和需求追踪矩阵。

## V1 边界

- 客户端只实现 Flutter iOS App，最低支持 iOS 16。
- 服务端使用 Java 21、Spring Boot 模块化单体和本机 SQLite WAL。
- Emby、LLM、ASR 与 MCP 的凭据仅保存在服务端。
- AI 只允许只读问答，不能修改任何业务数据。
- 不实现 Android、PC 学习 Web、桌面客户端、微服务、Redis、Kafka、向量数据库或多智能体。
