# AGENTS.md

本文件约束在“上岸（Shangan）”仓库内工作的所有编码代理。除非更高优先级指令明确覆盖，否则必须遵守以下规则。

## 基本工作要求

- 始终使用中文回复用户。
- 新增或修改的方法、类和复杂逻辑必须添加必要的中文注释，说明职责、边界或关键判断；不要添加重复代码含义的无效注释。
- 新增或修改的项目文档必须使用中文，专业术语、协议名、类名和 API 名称可以保留原语言。
- 禁止使用多智能体、子代理或并行代理完成本项目任务。
- 不得把聊天记录当作需求来源；聊天中的新要求必须先与冻结文档核对。
- 不实现当前活动版本 Task 和冻结文档之外的“顺手优化”、未来能力或宽泛脚手架；路线图不是实施授权。

## 项目目标

**上岸（Shangan）** 是一款面向 iPhone、iPad 和 Android 的 Flutter 学习监督 App。

V1 目标不是建设在线教育平台，而是完成以下闭环：

```text
考试目标
→ 今日作战单
→ Emby 视频学习 / 答题 / 模拟考试 / 专注计时
→ 日终自动结算
→ 学习欠债
→ 日报、周报和晚间审判
```

课程视频可由管理员批量导入完整全文和 Markdown 摘要，也可由服务端从 Emby 音频流调用 OpenAI-compatible ASR/LLM 自动生成。AI 可以生成待审核题目草稿，但只有管理员明确发布后才能进入正式题库。服务端不提供 AI Chat、智能体、MCP、联网搜索或 AI 业务写入口；当前 Flutter V1 也不包含 AI 入口。

## 必读文档与需求来源

任何实现前必须依次阅读：

1. `docs/specs/2026-08-27-shangan-v1-design.md`
2. `docs/plans/2026-08-27-shangan-v1-implementation-plan.md`
3. `docs/traceability/2026-08-27-shangan-v1-traceability.md`
4. `docs/roadmap/2026-09-04-shangan-version-roadmap.md`
5. 与当前 Task 相关的 ADR。

聊天记录不是需求来源。文档未包含的功能不进入 V1。

## 版本路线图与实施门禁

- 当前活动开发线默认为 V1.x.x。V1.x.x 固定现有需求和核心业务逻辑，只允许完成已批准 Task、修复 Bug，以及不改变业务语义的 UI 调整。
- V2.x.x 的方向是面向学习者的 AI 助教、学习伙伴升级和由服务端执行的日报/周报外部投递；V3.x.x 的方向是全 App 体验、服务端性能、多实例/多集群部署、外观个性化、课程仅音频播放、画中画和 PDF 资料阅读闭环。
- V2/V3 方向只记录在版本路线图中。路线图、原型、聊天和“未来可能需要”都不能直接触发代码、接口、迁移、依赖、资产或基础设施修改。
- 开始 V2 或 V3 前，必须分别完成并人工批准该版本的 Spec、相关 ADR、Implementation Plan 和 Traceability；编码代理只能执行获批计划中的当前 Task。
- V2 立绘必须在 V1「毛线团团」现有素材、动作模型和悬浮交互基础上增量修改，不得在 V1 提前另建宠物或 AI 入口。
- V3 多实例/多集群目标会触发 SQLite、本地文件、Session 和内容任务协调方案的重新设计；在 V3 ADR 获批前不得为此提前引入 PostgreSQL、Redis、Kafka、Kubernetes、微服务或双数据库兼容。

## 决策优先级

决策优先级：

1. 可信学习数据与状态机正确。
2. 用户能完成完整学习闭环。
3. 数据安全、可备份、可恢复。
4. 实现简单，符合少于 5 人同时在线的规模。
5. 未来 Android/Web 可复用服务端 API。
6. UI 精细化。

出现冲突时，靠前优先级覆盖靠后优先级。

## V1 范围

V1：

```text
Flutter Mobile App（iPhone / iPad / Android）
Spring Boot Backend
SQLite
Emby
Internal Admin Web
ASR / LLM content generation
OpenRouter model catalog cache
```

禁止实现：

```text
PC Study Web
macOS / Windows client
Redis
Kafka
microservices
vector database
multi-agent
offline video
DRM
payments
social features
AI Chat / agents / MCP
AI business writes
Flutter AI 入口
```

“未来可能需要”不是 V1 增加代码的理由。

## 仓库与架构边界

Repository:

```text
apps/ios      Flutter mobile application（历史目录名，包含 iOS/iPadOS 与 Android）
apps/server   Spring Boot modular monolith
docs          specs, plans, ADRs, API, runbooks
infra         container, Caddy, backup scripts
```

Server package-by-feature:

```text
common
identity
exam
catalog
planning
debt
learning
quiz
focus
reporting
ai.content
media.emby
admin
```

Feature internals may use:

```text
api
application
domain
infrastructure
```

Rules:

- Controllers call application services.
- Application services own transactions.
- Repositories own persistence.
- Domain objects own state transitions.
- Cross-feature access uses explicit application interfaces.
- No generic `CommonService`, `BaseService`, `Utils` dumping ground.
- No controller may use `JdbcClient` directly.
- No Flutter screen may call Dio directly; use repositories/controllers.
- Business truth lives on the server.

## 技术基线

- Flutter 3.44.x, locked with FVM.
- Dart 3.12.x.
- iOS minimum 16.
- Android minimum API 24.
- Riverpod, go_router, Dio.
- Flutter official `video_player`.
- Java 21.
- Spring Boot 4.1.x.
- Spring MVC with virtual threads.
- Spring JdbcClient / NamedParameterJdbcTemplate.
- SQLite WAL, one server instance.
- Flyway.
- Thymeleaf admin.
- LangChain4j OpenAI 1.19.0 stable API；不使用预发布 Spring Boot Starter、Agentic、MCP 或向量库模块。
- springdoc-openapi 3.x.

Do not guess dependency versions. Use the version pinned by FVM, the Maven BOM, and committed lockfiles. Do not introduce pre-release dependencies.

## 标准命令与本地启动

Root:

```bash
make format
make server-test
make ios-test
make verify
```

本地开发只运行本次新增或直接修改所对应的窄测试。`make server-test`、`make ios-test` 和 `make verify` 属于全量验证，只允许交给 GitHub CI 执行；Codex 本地不得运行。`make format` 仍可在提交前本地执行。

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

启动器会选择 Java 21，并在 Git 忽略的 `.run/jwt-secret` 中生成和复用本地开发 JWT 密钥。只有在已经正确注入 `JWT_SECRET` 等运行配置时，才直接执行 `./mvnw spring-boot:run`。

默认服务地址为 `http://127.0.0.1:18080`，健康检查为 `http://127.0.0.1:18080/actuator/health`。服务启动会对当前 `DATA_DIR` 指向的 SQLite 自动执行 Flyway；仅验证迁移时必须使用独立数据库备份副本，不得启动服务改写真实数据库。

iOS:

```bash
cd apps/ios
fvm flutter pub get
fvm dart format lib test integration_test
fvm flutter analyze
fvm flutter test
fvm flutter run
```

Task 1–3 use the narrow verification command specified in the plan while both applications are being bootstrapped. From Task 4 onward，本地只要求本次新增或直接修改的窄测试通过；全量 `make verify` 由 GitHub CI 验证。

## Task 开发流程

For each implementation Task:

1. Create or switch to a dedicated branch/worktree.
2. Read the Task's Files, Interfaces, and acceptance checks.
3. Write the specified failing test.
4. Run it and confirm the expected failure.
5. Implement the minimum complete behavior.
6. Run the narrow test.
7. 只补充运行本次新增或直接修改所对应的窄测试，不运行模块全量测试。
8. Run `make format`; push 后由 GitHub CI 运行 `make verify`。
9. Review diff for scope expansion, accidental secrets and missing tests.
10. Commit once with the Task commit subject.
11. Stop at review gates and report commands plus results.

Do not create broad scaffolding for later Tasks. A Task must deliver working behavior.

涉及 Spring Bean 构造器、配置绑定、数据库迁移或启动配置的改动，在自动化测试后还必须执行一次真实 ApplicationContext 启动 Smoke Test，并确认健康端点为 `UP`。自动化测试通过不等于应用一定能启动。

## 数据与时间规则

- IDs are UUID strings.
- Database timestamps are UTC Epoch Milliseconds.
- API timestamps are ISO-8601 UTC.
- User day boundaries use the user's IANA timezone.
- Inject `java.time.Clock`.
- Never call `Instant.now()`, `LocalDate.now()` or `System.currentTimeMillis()` directly in domain/application code.
- SQLite file must be on local disk.
- Hikari maximum pool size is 4.
- Required PRAGMAs:
  - WAL
  - foreign_keys ON
  - busy_timeout 5000
  - synchronous NORMAL
- Writes must use short transactions.
- Migrations are append-only after release. Never edit an applied migration.

## 核心状态机

### Daily Plan

```text
DRAFT → ACTIVE(LOCKED) → COMPLETED
                         ↘ CLOSED_WITH_DEBT
（ABANDONED 仅为 V1.3 之前的历史终态，只读兼容）
```

- 作战单只能通过带版本号的整单快照保存；未开始项目可改可删，已开始、待上传或已完成项目不可改不可删。
- A video plan item is complete only after trusted watch completion and, when `quiz_required=true`, quiz completion.
- 按 ADR-0011 与设计规范第 243 节，客户端不提供手动开摆；终态只由日终结算产生。
- Closing and debt generation are idempotent.

### Debt

```text
OPEN → PARTIALLY_REPAID → PAID
                    ↘ WAIVED by admin only
```

Debt records the original task component and remaining work. V1 debt types are `VIDEO_WATCH`, `QUIZ`, and `FOCUS`. A single video task may create both a watch debt and a quiz debt.

### Watch Session

```text
ACTIVE → PAUSED
ACTIVE/PAUSED → COMPLETED
ACTIVE/PAUSED → STOPPED
ACTIVE/PAUSED → EXPIRED
```

No client field can directly mark a video complete.

### Focus

```text
RUNNING → PAUSED → RUNNING
RUNNING/PAUSED → FINISHED
RUNNING/PAUSED → CANCELLED
```

Illegal transitions return stable business error codes.

## 可信播放规则

- Emby progress is not study progress.
- The server owns `maxVerifiedPositionMs`.
- Forward seek past trusted progress is rejected.
- Replay inside trusted progress is allowed.
- Heartbeat interval is 10 seconds.
- Duplicate sequence numbers do not double count.
- Background, pause or pending alive check does not count.
- App background always pauses video.
- Three heartbeat failures pause video.
- Completion threshold:
  - `duration - min(30 seconds, duration × 2%)`.
- No full video or HLS segment may be buffered into memory.
- Emby target host is fixed by configuration.
- Emby API Key never leaves the server.

## Emby 媒体库同步与稳定映射

- 管理员可从配置用户可见的 Emby 媒体库中选择来源，也可手工绑定 Series/Folder ID。
- 使用配置的 `EMBY_USER_ID` 和用户作用域 API 分页递归读取 `Movie`、`Episode`、`Video`；任一页失败不得写入部分快照。
- `media_items.id` 是不可变业务身份；`emby_item_id` 是可安全替换的当前播放源标识。
- Emby `Path` 只能在内存中用于生成带版本前缀的 SHA-256 来源指纹；原始路径禁止进入数据库、页面、日志和错误响应。
- 映射优先级为：当前 Item ID、课程内唯一来源指纹、无指纹历史课时的唯一标题与 2 秒内时长、管理员确认。
- 一对多、多对一或其他歧义不得自动合并，必须由管理员逐项确认。
- 重新绑定必须先完整读取远端快照，再在一个短事务中更新父节点、原位映射课时、创建新课时、标记不可用项并写审计。
- 父节点 404/无权限必须返回稳定错误并保留最后一次可用快照；只有父节点有效且完整结果确实为空时才可标记全部课时不可用。
- Item ID 变化只能更新原 `media_items` 行，不能替换本地 ID，因此可信进度、计划、欠债、题目、全文、摘要和内容任务必须继续关联原课时。

## 计划、欠债与日终规则

- 已激活作战单只能整单快照替换，不提供逐项写接口。
- 日终结算是唯一的欠债生成入口，不存在客户端手动开摆。
- `VIDEO_WATCH` debt uses remaining trusted duration and stores the baseline trusted position.
- `QUIZ` 欠债只在必答题未完成时生成；估算秒数是配置项 `QUIZ_DEBT_ESTIMATE_SECONDS`，提交完整答卷即结清。
- `FOCUS` debt uses planned minus completed seconds.
- A `DEBT_REPAYMENT` task never creates another debt.
- Debt generation is idempotent with one row per `(source_plan_item_id, debt_type)`.
- Direct trusted viewing and quiz completion reconcile matching open debt even outside a repayment plan.
- Client cannot waive debt.
- Completing a linked repayment task must reduce the exact debt.

## 课程学习内容规则

- 管理员每次为一个课程导入一个 ZIP。
- `manifest.json` 使用精确的 Emby Item ID 列出课时。
- 每集必须包含非空 UTF-8 `transcript.txt` 和 `summary.md`。
- 数据库事务开始前必须完成整包校验。
- 任意校验或写入失败都必须回滚整包。
- 重复导入覆盖匹配课时的旧内容。
- App API 只读，不提供移动端写接口。
- 转写直接使用 Emby 音频流，不下载完整视频，不在上岸服务端运行 FFmpeg。
- 内容任务全局串行；定时补全实现但默认关闭，开启后只补缺失全文或摘要。
- 长视频摘要和出题必须根据模型上下文预算递归分层处理，不得把超预算全文强行放进一次请求。
- OpenRouter 只缓存模型名称、上下文和能力；实际 LLM Base URL 可配置为 CPA。
- AI 题目只能写入草稿，管理员可审核并课程级批量发布；发布必须全批校验、事务写入且幂等。
- 禁止增加 AI Chat、智能体、MCP、联网搜索或 AI 对学习业务数据的写入。
- 禁止使用多智能体。

## 安全规则

Never commit:

```text
JWT secret
Emby API key
ASR API key
LLM API key
OpenRouter API key
admin password
production URL credentials
```

Other rules:

- Use HTTPS in production.
- Refresh tokens are hashed at rest.
- Passwords use BCrypt strength 12.
- Admin uses secure HttpOnly session cookies and CSRF.
- API uses Bearer tokens.
- Playback tickets are HMAC signed and short-lived.
- Proxy code must reject arbitrary hosts and path traversal.
- Logs must redact Authorization, cookies, API keys and media credentials.
- Error responses never include stack traces.

## 测试规则

Server:

- JUnit 5.
- AssertJ.
- MockMvc.
- 使用 WireMock 测试 Emby、ASR、LLM 和 OpenRouter。
- ZIP 解析逻辑测试必须覆盖整包校验和重复覆盖命令生成。
- 内容任务测试必须覆盖 NDJSON 拼接、全局串行、长文本分层、临时音频删除和题目草稿批量发布。
- 状态机、策略和应用服务使用 Fake、Stub 或 Mock Repository 做纯逻辑测试。
- 自动化测试不得启动 SQLite、Flyway 或其他真实数据库，也不测试具体 SQL、数据库约束、事务和迁移。
- Controller、安全规则和外部服务协议可以使用不连接数据库的测试切片或 WireMock。

Flutter:

- Unit tests for controllers and parsers.
- Widget tests for critical confirmation flows.
- Player tests use a fake adapter.
- Physical iPhone smoke test is mandatory for real playback.

Do not:

- disable tests;
- replace exact assertions with `isNotNull`;
- mock the code under test;
- use arbitrary sleeps when a fake Clock or completer works;
- accept flaky tests.

## API 规则

- Prefix `/api/v1`.
- Use RFC Problem Details.
- Stable `errorCode`.
- Success responses are direct DTOs.
- No `/ios` route names.
- Ownership validation on every user resource.
- Heartbeat sequence is monotonic and idempotent.
Update `docs/api/openapi.yaml` with API changes. Contract drift must fail CI.

## UI 规则

- V1 light theme only.
- Use system typography and Dynamic Type.
- Minimum tap target 44pt.
- Important state must not rely on color alone.
- iPhone、iPad 和 Android 共享业务体验，平台差异仅放在布局和系统适配层。
- Alive check modal cannot be dismissed by tapping outside.
- Do not add unsolicited gamification or AI features.

## 运行与备份

- One server process.
- Local `/data/study.db`.
- Daily online SQLite backup using `.backup`.
- Run `PRAGMA integrity_check` on backup.
- Keep 7 daily and 4 weekly backups.
- Actuator health endpoint.
- Request ID in logs.
- 外部调用必须设置超时。
- Production image runs as non-root.

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

- required behavior exists;
- narrow tests pass;
- GitHub CI 中的 full verification passes；本地不运行全量测试；
- 涉及启动边界时，真实 ApplicationContext 启动和健康检查通过；
- API docs are current;
- no secret or debug artifact is present;
- logs and errors are safe;
- diff is limited to the Task;
- commit is clean and reviewable.

V1 仅在所有验收场景、人工备份恢复，以及物理 iPhone、iPad 和 Android 设备验证通过后完成。
