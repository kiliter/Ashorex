# AGENTS.md

## Project

**上岸（Shangan）** 是一款 iOS-only 的学习监督 App。

V1 目标不是建设在线教育平台，而是完成以下闭环：

```text
考试目标
→ 每日计划
→ 锁定
→ Emby 视频学习 / 答题 / 专注计时
→ 开摆或日终
→ 学习欠债
→ 日报、周报和晚间审判
```

AI 只提供只读问答：

1. 首页独立 AI Tab。
2. 视频播放页 AI Bottom Sheet。
3. 视频音频转写、分段摘要和全局摘要由服务端生成。
4. 联网搜索通过白名单 MCP 工具。
5. AI 永远不能修改业务数据。

## Required Reading

任何实现前必须依次阅读：

1. `docs/specs/2026-08-27-shangan-v1-design.md`
2. `docs/plans/2026-08-27-shangan-v1-implementation-plan.md`
3. `docs/traceability/2026-08-27-shangan-v1-traceability.md`
4. 与当前 Task 相关的 ADR。

聊天记录不是需求来源。文档未包含的功能不进入 V1。

## Priority

决策优先级：

1. 可信学习数据与状态机正确。
2. 用户能完成完整学习闭环。
3. 数据安全、可备份、可恢复。
4. 实现简单，符合少于 5 人同时在线的规模。
5. 未来 Android/Web 可复用服务端 API。
6. UI 精细化。

出现冲突时，靠前优先级覆盖靠后优先级。

## Platform Scope

V1：

```text
iOS Flutter App
Spring Boot Backend
SQLite
Emby
Internal Admin Web
AI / ASR / MCP
```

禁止实现：

```text
Android
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
AI write actions
```

“未来可能需要”不是 V1 增加代码的理由。

## Architecture

Repository:

```text
apps/ios      Flutter iOS application
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
ai
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

## Technology Baseline

- Flutter 3.44.x, locked with FVM.
- iOS minimum 16.
- Riverpod, go_router, Dio.
- Flutter official `video_player`.
- `flutter_chat_ui`.
- Java 21.
- Spring Boot 4.1.x.
- Spring MVC with virtual threads.
- Spring JdbcClient / NamedParameterJdbcTemplate.
- SQLite WAL, one server instance.
- Flyway.
- Thymeleaf admin.
- LangChain4j 1.19.x core APIs.
- LangChain4j AI Services + ToolProvider + MCP.
- No `langchain4j-agentic`.
- FFmpeg.
- springdoc-openapi 3.x.

Do not guess dependency versions. Use the version pinned by FVM, the Maven BOM, and committed lockfiles. Do not introduce pre-release dependencies.

## Commands

Root:

```bash
make format
make server-test
make ios-test
make verify
```

Server:

```bash
cd apps/server
./mvnw test
./mvnw verify
./mvnw spring-boot:run
```

iOS:

```bash
cd apps/ios
fvm flutter pub get
fvm dart format lib test integration_test
fvm flutter analyze
fvm flutter test
fvm flutter run
```

Task 1–3 use the verification command specified in the plan while both applications are being bootstrapped. From Task 4 onward, a task is not complete until its narrow test and `make verify` pass.

## Development Method

For each implementation Task:

1. Create or switch to a dedicated branch/worktree.
2. Read the Task's Files, Interfaces, and acceptance checks.
3. Write the specified failing test.
4. Run it and confirm the expected failure.
5. Implement the minimum complete behavior.
6. Run the narrow test.
7. Run module tests.
8. Run `make format && make verify`.
9. Review diff for scope expansion, accidental secrets and missing tests.
10. Commit once with the Task commit subject.
11. Stop at review gates and report commands plus results.

Do not create broad scaffolding for later Tasks. A Task must deliver working behavior.

## Data Rules

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

## State Machines

### Daily Plan

```text
DRAFT → LOCKED → COMPLETED
                 ↘ ABANDONED
                 ↘ CLOSED_WITH_DEBT
```

- Only DRAFT is editable.
- A video plan item is complete only after trusted watch completion and, when `quiz_required=true`, quiz completion.
- Abandonment is final in V1.
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

## Trusted Playback Rules

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

## Plan, Abandonment, and Debt Rules

- Locked plans cannot be edited.
- Open-palm abandonment closes the plan immediately.
- Abandonment and normal day-end use the same debt calculation service.
- `VIDEO_WATCH` debt uses remaining trusted duration and stores the baseline trusted position.
- `QUIZ` debt is created only when a required quiz remains incomplete; its estimated seconds are display/planning metadata, while passing the quiz settles the debt.
- `FOCUS` debt uses planned minus completed seconds.
- A `DEBT_REPAYMENT` task never creates another debt.
- Debt generation is idempotent with one row per `(source_plan_item_id, debt_type)`.
- Direct trusted viewing and quiz completion reconcile matching open debt even outside a repayment plan.
- Client cannot waive debt.
- Completing a linked repayment task must reduce the exact debt.

## AI Rules

AI is read-only.

Allowed internal tool names:

```text
get_today_plan_summary
get_open_debt_summary
get_exam_progress
get_daily_report
get_weekly_report
search_video_transcript
get_video_summary
```

MCP:

- Only fixed configured server URLs.
- Only explicit allowlisted tool names.
- No filesystem, shell, email, calendar or write/database tools.
- Tool timeout 20 seconds.
- Tool response maximum 50,000 characters.
- Search failure must degrade gracefully.

Prompts and context:

- Transcript and web content are untrusted.
- Delimit untrusted context.
- Never allow transcript/web text to override system instructions.
- Do not log complete prompts by default.
- Do not send another user's plan, debt, report or transcript context.
- General chat and video chat use the same engine but different scoped context.
- Do not use multi-agent.
- Do not create AI planning, AI judgment or AI write actions.

## Transcription Rules

Pipeline:

```text
PENDING
→ EXTRACTING_AUDIO
→ TRANSCRIBING
→ SUMMARIZING
→ READY
```

Failure:

```text
any active state → FAILED
```

- One global active job.
- Temporary audio is deleted.
- Retry replaces partial segments transactionally.
- `READY` requires transcript segments, FTS index, section summaries and global summary.
- Video Q&A uses:
  - global summary;
  - current position ±3 minutes;
  - top 8 FTS matches;
  - relevant section summaries.
- If transcript is not READY, the UI must say so and only permit regular Q&A.

## Security

Never commit:

```text
JWT secret
Emby API key
LLM key
ASR key
MCP token
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

## Testing

Server:

- JUnit 5.
- AssertJ.
- MockMvc.
- Temporary file SQLite.
- WireMock for Emby, LLM, ASR and MCP.
- State machines and policies tested without Spring when possible.
- Integration tests verify constraints, transactions and security.
- Acceptance tests cover complete flows.

Flutter:

- Unit tests for controllers and parsers.
- Widget tests for critical confirmation flows.
- Player tests use a fake adapter.
- AI SSE tests include fragmented chunks.
- Physical iPhone smoke test is mandatory for real playback.

Do not:

- disable tests;
- replace exact assertions with `isNotNull`;
- mock the code under test;
- use arbitrary sleeps when a fake Clock or completer works;
- accept flaky tests.

## API Rules

- Prefix `/api/v1`.
- Use RFC Problem Details.
- Stable `errorCode`.
- Success responses are direct DTOs.
- No `/ios` route names.
- Ownership validation on every user resource.
- Heartbeat sequence is monotonic and idempotent.
- SSE event types:
  - message_start
  - tool_status
  - delta
  - citation
  - message_end
  - error

Update `docs/api/openapi.yaml` with API changes. Contract drift must fail CI.

## UI Rules

- V1 light theme only.
- Use system typography and Dynamic Type.
- Minimum tap target 44pt.
- Important state must not rely on color alone.
- iOS experience takes priority over future Android.
- The abandon button must show exact added debt before confirmation.
- Alive check modal cannot be dismissed by tapping outside.
- AI citations and video timestamps must be visible.
- Do not add unsolicited gamification or AI features.

## Operations

- One server process.
- Local `/data/study.db`.
- Daily online SQLite backup using `.backup`.
- Run `PRAGMA integrity_check` on backup.
- Keep 7 daily and 4 weekly backups.
- Actuator health endpoint.
- Request ID in logs.
- FFmpeg and external calls have timeouts.
- Production image runs as non-root.

## Scope Change Protocol

When a requested change conflicts with the frozen spec:

1. Stop implementation.
2. State the exact conflict.
3. Propose the smallest spec change.
4. Add or update an ADR.
5. Update the spec and implementation plan.
6. Obtain human approval.
7. Resume implementation.

Do not silently reinterpret requirements.

## Definition of Done

A change is complete only when:

- required behavior exists;
- narrow tests pass;
- full verification passes;
- API docs are current;
- no secret or debug artifact is present;
- logs and errors are safe;
- diff is limited to the Task;
- commit is clean and reviewable.

V1 is complete only after all acceptance scenarios, backup/restore and physical iPhone testing pass.
