# 上岸 V1 Codex 交接入口

## 交接包用途

本目录是一套可以直接放入空 Git 仓库、交给 Codex 执行的 V1 交接资料。它包含产品边界、技术设计、分任务实现计划、智能体约束和需求追踪，不包含已经实现的业务代码。

## 阅读顺序

1. `AGENTS.md`
2. `docs/specs/2026-08-27-shangan-v1-design.md`
3. `docs/plans/2026-08-27-shangan-v1-implementation-plan.md`
4. `docs/traceability/2026-08-27-shangan-v1-traceability.md`
5. `CODEX_START_PROMPT.md`

发生冲突时，优先级如下：

```text
AGENTS.md 中的安全与范围硬约束
    ↓
产品与技术设计规范
    ↓
实施计划
    ↓
Codex 在实现中的局部判断
```

聊天记录不是需求来源。

## 已冻结的关键决策

- V1 客户端使用同一 Flutter 工程支持 iPhone、iPad 和 Android。
- PC Web、macOS 和 Windows 只保留服务端 API 兼容设计，不实现客户端。
- 服务端采用 Java 21、Spring Boot 模块化单体。
- 数据库采用单机 SQLite WAL；不引入 PostgreSQL、Redis、Kafka 或微服务。
- Emby 负责视频库、剧集、媒体信息和转码；上岸服务端负责业务规则和媒体代理。
- 学习进度以服务端可信进度为准，不采用 Emby 播放进度作为学习进度。
- 锁定后的计划不可静默删除；开摆会将所有剩余任务立即转换成学习欠债。
- AI 只有首页只读问答和视频只读问答两种入口。
- 视频完整转写由后端处理，生成分段摘要和全局摘要；问答通过全文检索和摘要构造上下文。
- 联网搜索通过白名单 MCP 工具；AI 无任何业务写工具。
- 管理后台采用 Spring MVC + Thymeleaf，不单独创建前端项目。

## 推荐执行方式

1. 将本目录内容复制到空仓库根目录。
2. 初始化 Git，并提交交接文档作为基线提交。
3. 把 `CODEX_START_PROMPT.md` 的内容作为 Codex 第一条执行指令。
4. Codex 按实施计划的 Task 1 到 Task 16 顺序执行。
5. 每个 Task 独立测试、评审和提交；不得跨 Task 混合提交。
6. 从 Task 4 起，每次提交前必须执行：

```bash
make format
make verify
```

7. Task 16 完成后，按设计规范第 27 节逐场景验收，并执行备份恢复演练。

## 不允许 Codex 自行增加的内容

- PC Web 或桌面客户端。
- 社交、排行榜、监督人、支付、直播、离线视频、DRM。
- AI 主动催学、AI 自动计划、AI 修改数据、多智能体。
- 向量数据库、Redis、Kafka、微服务、Kubernetes。
- 因“未来可能需要”而创建的空抽象层、空模块或双数据库实现。

## 完成判定

只有同时满足以下条件才算 V1 完成：

- Task 1–16 全部有独立提交。
- 后端测试、Flutter 测试、静态检查和格式检查全部通过。
- iPhone、iPad 和 Android 真机可以完成完整学习闭环。
- 服务端自动化测试不启动 SQLite、Flyway 或其他真实数据库。
- Emby Key、LLM Key、ASR Key 和 MCP 凭据未进入客户端、日志或响应。
- 开摆与日终欠债生成具备幂等性。
- 可信观看规则无法通过普通拖动进度条绕过。
- AI 只读约束有自动化测试。
- SQLite 备份和恢复演练成功。
