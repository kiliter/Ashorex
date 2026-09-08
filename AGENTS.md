# AGENTS.md

本文件约束在“上岸（Shangan）”仓库内工作的所有编码代理。除非更高优先级指令明确覆盖，否则必须遵守以下规则。

## 基本工作要求

- 始终使用中文回复用户。
- 新增或修改的方法、类和复杂逻辑必须添加必要的中文注释，说明职责、边界或关键判断；不要添加重复代码含义的无效注释。
- 新增或修改的项目文档必须使用中文，专业术语、协议名、类名和 API 名称可以保留原语言。
- 不得把聊天记录当作需求来源；聊天中的新要求必须先与冻结文档核对。
- 不实现当前活动版本 Task 和冻结文档之外的“顺手优化”、未来能力或宽泛脚手架。

## 项目目标

**上岸（Shangan）** 是一款面向 iPhone、iPad 和 Android 的 Flutter 学习监督 App。

V2 要闭合的链路：

```text
考试目标倒计时看板
→ 今日 Todo（课程 / 专注计时 / 待办事项）
→ 执行（看视频 / 倒计时专注 / 手动勾选）
→ 进度与时长上报
→ 附件与备注回填
→ 日 / 周 / 月统计
→ App 心跳与在线判定
→ 服务端扫描未完成 → 催办（客户端全屏 / Server 酱）
→ 督学人查看与一键督学
```

监督压力来自**真人督学 + 催办留痕**，不来自自动惩罚或播放校验。

## 必读文档与需求来源

任何实现前必须依次阅读：

1. `docs/specs/2026-09-07-shangan-v2-design.md`
2. `docs/plans/2026-09-07-shangan-v2-implementation-plan.md`
3. `docs/traceability/2026-09-07-shangan-v2-traceability.md`
4. `docs/prototypes/shangan-v2-prototype.html`（唯一 UI 事实来源）
5. 与当前 Task 相关的 ADR（V2 为 ADR-0025 ~ ADR-0035）。

聊天记录不是需求来源。文档未包含的功能不进入 V2。

## V2 范围

实现：

```text
Flutter Mobile App（iPhone / iPad / Android）
Spring Boot Backend
SQLite
Emby（唯一媒体源与元数据来源）
Internal Admin Web（Vue 3 SPA + /admin/api 内部接口）
Server 酱（催办推送）
督学端（App 内角色切换）
```

禁止实现：

```text
可信播放 / 播放票据 / 进度验活
学习欠债 / 答题 / 题库 / 模拟考试
日终结算 / 日报 / 周报 / 晚间审判
AI 内容生产（ASR / LLM / OpenRouter / 全文 / 摘要 / 题目草稿）
AI Chat / 智能体 / MCP / 联网搜索
视觉伴学立绘
PC Study Web / macOS / Windows client
Redis / Kafka / 微服务 / 向量数据库
支付 / 商城 / 社交 / 离线视频 / DRM
```

材料（PDF）按 ADR-0030 只预留模型与开关，V2 不实现阅读页、书籍库同步、下载代理与页数回报。

“未来可能需要”不是增加代码的理由。

## 决策优先级

1. 学习数据与状态正确。
2. 用户能完成完整学习闭环。
3. 数据安全、可备份、可恢复。
4. 实现简单，符合少于 5 人同时在线的规模。
5. 未来 Android/Web 可复用服务端 API。
6. UI 精细化。

出现冲突时，靠前优先级覆盖靠后优先级。

## 仓库与架构边界

Repository:

```text
apps/ios      Flutter mobile application（历史目录名，包含 iOS/iPadOS 与 Android）
apps/server   Spring Boot modular monolith
apps/admin-web 内部管理后台（Vue 3 SPA），构建产物打进服务端静态资源
docs          specs, plans, ADRs, API, prototypes, runbooks
infra         container, Caddy, backup scripts
```

Server package-by-feature:

```text
common        配置、异常、请求日志、UUID、运行配置
identity      用户、角色、JWT、时区换算
supervision   督学绑定、权限校验、督学端视图
goal          考试目标
catalog       课程、学习资源、Emby 元数据投影与同步
todo          Todo、进度、专注、附件、删除台账
presence      心跳与在线状态
nag           催办策略、扫描、渠道、回应
stats         统计聚合
archive       归档与级联彻底删除
media.emby    Emby 客户端与流代理
admin         管理后台内部 REST 接口（/admin/api，仅服务 admin-web）
```

Feature internals may use `api` / `application` / `domain` / `infrastructure`。

Rules:

- Controllers call application services.
- Application services own transactions.
- Repositories own persistence.
- Domain objects own state transitions.
- Cross-feature access uses explicit application interfaces（例如 `UserTimeService`、`ResourceProgressPort`、`SupervisionGuard`）。
- No generic `CommonService`、`BaseService`、`Utils` dumping ground。
- No controller may use `JdbcClient` directly.
- No Flutter screen may call Dio directly；只能通过 `ShanganRepository`。
- Business truth lives on the server：是否达标、是否完成一律由服务端裁决。

## 技术基线

- Flutter 3.44.x, locked with FVM；Dart 3.12.x。
- iOS minimum 16；Android minimum API 24。
- Riverpod、go_router、Dio、官方 `video_player`。
- Java 21；Spring Boot 4.1.x；Spring MVC with virtual threads。
- Spring JdbcClient / NamedParameterJdbcTemplate。
- SQLite WAL，单实例；Flyway。
- 管理后台为 Vue 3 SPA（`apps/admin-web`），经 `/admin/api/**` 内部接口取数；见 ADR-0033。
- springdoc-openapi 3.x。

不要猜测依赖版本。使用 FVM、Maven BOM 与锁文件中固定的版本，不引入预发布依赖。

Spring Boot 4 的 Web 层默认使用 Jackson 3，容器中不再自动注册 Jackson 2 的 `ObjectMapper`；本仓库在 `ApplicationConfiguration` 中显式提供一个，供 Emby 解析与配置 JSON 使用。

## 标准命令与本地启动

Root:

```bash
make format
make server-test
make ios-test
make verify
```

本地开发只运行本次新增或直接修改所对应的窄测试。`make server-test`、`make ios-test` 和 `make verify` 属于全量验证，只允许交给 GitHub CI 执行。`make format` 仍可在提交前本地执行。

Server:

```bash
cd apps/server
./mvnw test
./mvnw verify
```

本地启动后端必须优先在仓库根目录使用：

```bash
./run.sh server
```

启动器会选择 Java 21，并在 Git 忽略的 `.run/jwt-secret` 中生成和复用本地开发 JWT 密钥。

默认服务地址为 `http://127.0.0.1:18080`，健康检查为 `http://127.0.0.1:18080/actuator/health`，管理后台入口为 `/admin`。

修改数据库迁移后必须先 `./mvnw clean`，否则 `target/classes` 中的旧迁移会与新 baseline 冲突并报 “Found more than one migration with version 001”。

iOS:

```bash
cd apps/ios
export PATH="$HOME/.pub-cache/bin:$PATH"
fvm flutter pub get
fvm dart format lib test
fvm flutter analyze
fvm flutter test
fvm flutter run
```

## Task 开发流程

For each implementation Task:

1. Create or switch to a dedicated branch/worktree.
2. Read the Task's Files, Interfaces, and acceptance checks.
3. Write the specified failing test.
4. Run it and confirm the expected failure.
5. Implement the minimum complete behavior.
6. Run the narrow test.
7. 只补充运行本次新增或直接修改所对应的窄测试，不运行模块全量测试。
8. Run `make format`；push 后由 GitHub CI 运行 `make verify`。
9. Review diff for scope expansion, accidental secrets and missing tests.
10. Commit once with the Task commit subject.
11. Stop at review gates and report commands plus results.

> **V2 首轮实施的临时例外**：为了快速迭代，V2 首轮不写单元测试，直接完成代码并做全量编译与真实启动验证，测试统一在实施计划的 T36 补齐。该例外只适用于「V2 首轮实施」，不构成后续跳过测试的先例。各 Task 中列出的测试用例清单保留不删，作为 T36 的实施依据。

涉及 Spring Bean 构造器、配置绑定、数据库迁移或启动配置的改动，在自动化测试后还必须执行一次真实 ApplicationContext 启动 Smoke Test，并确认健康端点为 `UP`。自动化测试通过不等于应用一定能启动。

## 数据与时间规则

- IDs are UUID strings.
- Database timestamps are UTC Epoch Milliseconds.
- API timestamps are ISO-8601 UTC.
- `localDate` 是用户时区下的 `YYYY-MM-DD`，是「今日 / 历史某天」的唯一依据。
- User day boundaries use the user's IANA timezone；跨模块换算必须通过 `UserTimeService`。
- Inject `java.time.Clock`。
- Never call `Instant.now()`、`LocalDate.now()` 或 `System.currentTimeMillis()` directly in domain/application code.
- SQLite file must be on local disk；Hikari maximum pool size is 4。
- Required PRAGMAs：WAL、`foreign_keys ON`、`busy_timeout 5000`、`synchronous NORMAL`。
- Writes must use short transactions.
- Migrations are append-only after release。V2 baseline（`V001__shangan_v2_baseline.sql`）已发布，后续变更必须新增 `V002`、`V003`……

## 核心状态机

### Todo

```text
TODO → IN_PROGRESS → DONE
```

- 没有 `ABANDONED`、没有 `CLOSED_WITH_DEBT`、没有日终自动终态。
- 未完成的历史 Todo 保持原状，由「未完成汇总」跨日期聚合，支持顺延、补记完成（必填备注）、删除（必填原因）。
- 课程 Todo 完成判定由服务端裁决：`position / duration * 1000 >= targetProgressPermille`，或用户手动标记完成。
- 删除是物理删除 `todos` 行 + 写 `todo_deletions` 台账，任何状态都可删，但**必须填原因**。

### 专注（Todo 内嵌）

```text
IDLE → RUNNING ⇄ PAUSED
RUNNING/PAUSED → FINISHED     判定完成
RUNNING/PAUSED → STOPPED      停止本轮，未完成；再次开始从零计时
IDLE/RUNNING/PAUSED/STOPPED → ABANDONED  跳过，未完成，保留总时长
STOPPED → RUNNING            开始新一轮，历史时长不抵扣倒计时
```

同一用户同一时刻只允许一个 `RUNNING`（部分唯一索引兜底）。非法转移返回 `FOCUS_ILLEGAL_TRANSITION`。

### 催办

```text
PENDING → DELIVERED → RESPONDED | EXPIRED
```

渠道降级：在线 → 全屏；离线或全屏超时未回应 → Server 酱。自动催办幂等键为 `(user_id, local_date, threshold_level)`。

### 归档与删除

```text
ACTIVE → ARCHIVED → 彻底删除
```

列表页只提供归档；彻底删除只在后台归档区，必须走级联清单并输入名称确认。

## 播放与进度规则

- V2 没有可信播放：进度条可自由拖动，支持快退 / 快进 10 秒、倍速 0.75–2.0、全屏。
- 服务端只接收 `positionMs`（取 max，单调不回退）与 `deltaWatchedMs`（真实经过时间，仅前台累计）。
- 倍速不改变时长口径。
- `(todoId, clientSeq)` 唯一保证客户端重放幂等；断网时本地排队，恢复后按序重放。
- 上报节奏：播放中每 15 秒一次，暂停 / 退出 / 完成 / 切后台各补一次。
- 前台播放保持屏幕常亮，暂停、退出时释放。
- 不得把完整视频或 HLS 分片缓冲进内存；Emby 目标主机由配置固定；Emby API Key 不出服务端。

## 心跳与催办规则

- 心跳固定周期由服务端下发（默认 60 秒），只更新在线状态。
- **心跳不是有效操作**：空闲时长只由进度上报、完成、勾选、附件上传、创建 / 删除 Todo、催办回应刷新。
- 不保存心跳明细历史，只维护 `user_presence` 单行。
- 催办阈值、渠道、免打扰、扫描周期只在服务端管理后台配置；App 端只读展示。
- 全屏催办不可点击外部关闭、不可返回，必须填写原因（默认最少 5 字）才能提交。

## Emby 元数据与同步规则

- 课程库的筛选维度（流派 / 标签 / 人物 / 年份）全部来自 Emby，本项目不维护这些主数据，也不提供编辑入口。
- 三张只读投影表（`course_genres` / `course_tags` / `course_people`）每次同步整表重写。
- `learning_resources.id` 是不可变业务身份，永不重建；`external_ref` 是可替换的当前来源标识。
- Emby `Path` 只能在内存中用于生成带版本前缀的 SHA-256 来源指纹；原始路径禁止进入数据库、页面、日志和错误响应。
- 映射优先级：当前 `external_ref` → 课程内唯一来源指纹 → 唯一标题且时长差 ≤2 秒 → 管理员确认。
- 一对多、多对一或其他歧义不得自动合并，必须逐项人工确认。
- 任一页读取失败不得写入部分快照；父节点 404 时标记 `source_missing` 并保留上次可用快照。
- 元数据改名、重打标签、增删人物**不触发任何数据迁移**；统计按 ID 聚合，历史结果不变。
- 课时在远端消失只标记 `available=0`，不删除本地行；引用它的 Todo 只能补记完成或删除。

## 安全规则

Never commit:

```text
JWT secret
Emby API key
Server 酱 SendKey
admin password
production URL credentials
```

Other rules:

- Use HTTPS in production.
- Refresh tokens are hashed at rest；Passwords use BCrypt strength 12。
- Admin uses secure HttpOnly session cookies and CSRF；API uses Bearer tokens。
- 附件存储在 `DATA_DIR/attachments/{userId}/`，文件名由服务端生成，禁止使用客户端原始路径；下载需归属校验。
- Proxy code must reject arbitrary hosts and path traversal.
- Logs must redact Authorization、cookies、API keys、媒体凭据与 Emby 路径。
- Error responses never include stack traces.
- 外部调用必须设置超时。

## 测试规则

Server:

- JUnit 5、AssertJ、MockMvc；使用 WireMock 测试 Emby 与 Server 酱。
- 自动化测试不得启动 SQLite、Flyway 或其他真实数据库，也不测试具体 SQL、数据库约束、事务和迁移。
- Controller、安全规则和外部服务协议可以使用不连接数据库的测试切片或 WireMock。
- 必须覆盖：进度上报幂等与单调、目标进度达标判定、专注两种终态、删除原因校验与台账、催办阈值 / 分档 / 幂等 / 免打扰 / 渠道降级、督学权限校验、Emby 映射优先级与歧义拒绝、级联删除顺序、统计聚合口径。

Flutter:

- Unit tests for controllers and parsers。
- Widget tests for critical confirmation flows（删除原因、凭证必填、全屏催办）。
- Player tests use a fake adapter。
- Physical iPhone / iPad / Android smoke test is mandatory for real playback。

Do not:

- disable tests；
- replace exact assertions with `isNotNull`；
- mock the code under test；
- use arbitrary sleeps when a fake Clock or completer works；
- accept flaky tests。

## API 规则

- Prefix `/api/v1`。
- Use RFC 9457 Problem Details，带稳定 `errorCode`。
- Success responses are direct DTOs。
- No `/ios` route names。
- Ownership validation on every user resource；督学端接口必须过 `SupervisionGuard`。
- 进度上报与心跳必须幂等且低开销。

Update `docs/api/openapi.yaml` with API changes。Contract drift must fail CI。

## UI 规则

- V2 light theme only。
- Use system typography and Dynamic Type。
- Minimum tap target 44pt。
- Important state must not rely on color alone。
- iPhone、iPad 和 Android 共享业务体验，平台差异仅放在布局和系统适配层。
- 全屏催办弹框不可点击外部关闭。
- UI 必须与 `docs/prototypes/shangan-v2-prototype.html` 一致；不一致时先确认改原型还是改实现，不要各自漂移。
- Do not add unsolicited gamification or AI features。

## 运行与备份

- One server process；Local `/data/study.db`。
- Daily online SQLite backup using `.backup`；Run `PRAGMA integrity_check` on backup。
- Keep 7 daily and 4 weekly backups。
- 每日备份后执行孤儿数据自检，结果写运行日志。
- Actuator health endpoint；Request ID in logs。
- Production image runs as non-root。

## 范围变更流程

When a requested change conflicts with the frozen spec:

1. Stop implementation.
2. State the exact conflict.
3. Propose the smallest spec change.
4. Add or update an ADR.
5. Update the spec and implementation plan.
6. Obtain human approval.
7. Resume implementation.

Do not silently reinterpret requirements.

## 完成定义

A change is complete only when:

- required behavior exists；
- narrow tests pass（V2 首轮实施期间为编译与静态分析通过，测试见 T36）；
- GitHub CI 中的 full verification passes；
- 涉及启动边界时，真实 ApplicationContext 启动和健康检查通过；
- API docs are current；
- no secret or debug artifact is present；
- logs and errors are safe；
- diff is limited to the Task；
- commit is clean and reviewable。

V2 仅在所有验收场景、人工备份恢复，以及物理 iPhone、iPad 和 Android 设备验证通过后完成。
