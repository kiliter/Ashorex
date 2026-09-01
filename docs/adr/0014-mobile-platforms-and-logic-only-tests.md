# ADR-0014：移动端扩展到 iPhone、iPad 与 Android，并采用纯逻辑自动化测试

- 状态：已接受
- 日期：2026-09-01
- 取代：ADR-0001 的仅 iOS 平台范围

## 背景

原 V1 只交付 iPhone 客户端，并使用真实 SQLite、Flyway 和 Spring Boot 上下文执行大量数据库集成测试。实际交付需要同一套 Flutter 代码后续覆盖 iPad 和 Android；同时，真实数据库测试会把业务断言与迁移、连接池、调度器和 SQLite 锁竞争混在一起，导致失败原因难以定位。

GitHub 干净环境中的 Flutter 3.44.7 stable 自带 Dart 3.12.2，官方支持 iOS 与 Android。当前直接依赖均兼容 Dart 3.12 和 Flutter 3.44，因此不需要升级到尚未纳入项目稳定基线的新版本。

## 决策

Flutter 移动端以 iPhone、iPad 和 Android 为目标平台，继续固定 Flutter 3.44.7 stable 与其配套的 Dart 3.12.x。iOS 最低版本保持 16；Android 使用 compileSdk 37，最低运行版本设为 API 24。移动端共享业务代码、Repository、Controller 和 API 合同，允许在布局、系统能力和播放器适配层做必要的平台差异处理。

服务端自动化测试只保留不连接真实数据库的逻辑测试，包括领域状态机、应用服务编排、策略、解析器、外部服务协议、Controller 切片和安全规则。测试通过 Fake、Stub 或 Mock 表达 Repository 边界，不启动 SQLite、Flyway，也不验证具体 SQL、数据库约束、事务或迁移结果。现有数据库运行时测试删除；业务价值明确的场景改写为纯逻辑测试。

SQLite 迁移、备份恢复和真实移动端构建属于发布前人工验证与运行检查，不作为日常服务端自动化测试的一部分。生产代码仍保留 Flyway、约束、事务、备份和恢复实现。

## 后果

- Flutter SDK、Dart 约束、依赖锁文件和 CI 使用同一份稳定版本，不再依赖本机污染的 FVM 缓存。
- iPad 复用 iOS 工程；Android 使用同一 Flutter 工程，不复制业务实现。
- 服务端测试速度和确定性提高，不再因后台调度器与 SQLite 竞争产生 `SQLITE_BUSY_SNAPSHOT`。
- 自动化测试不再发现 SQL 拼写、迁移顺序、数据库约束和真实事务问题，这些风险转移到代码评审、启动检查、人工发布验证和备份恢复演练。
- OpenAPI、外部服务协议和移动端 Controller/Widget 测试继续保留，因为它们不依赖真实数据库。

## 被否决的方案

- 升级到未成为当前官方稳定文档基线的 Flutter 版本：没有解决本项目依赖兼容性的额外收益，并扩大工具链变化。
- 为 iPad 单独创建客户端：iPadOS 与 iOS 共用 Flutter iOS 工程，只需要响应式布局和设备验收。
- 通过 SQLite 重试、延时或缩小连接池修复测试：会掩盖测试环境与生产调度器未隔离的问题。
- 保留少量数据库集成测试：与本次确认的“自动化测试只保留业务逻辑测试”边界不一致。
