# 上岸 V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一款 iOS-only 的学习监督 App，完成 Emby 视频学习、可信观看、计划锁定、开摆欠债、答题、计时、报表以及只读 AI/MCP 问答闭环。

**Architecture:** 单仓库包含 Flutter iOS App 与 Spring Boot 模块化单体。服务端以 SQLite 为业务真相，Emby 提供媒体能力，AI Runtime、转写、摘要和 MCP 都运行在后端；Flutter 只负责交互、播放器、心跳和 SSE 渲染。

**Tech Stack:** Flutter 3.44.7、flutter_riverpod 3.0.2、go_router 17.5.0、Dio 5.11.0、video_player 2.14.0、flutter_chat_ui 2.11.0、Java 21、Spring Boot 4.1.1、Spring MVC、JdbcClient、sqlite-jdbc 3.53.4.0、SQLite WAL、Flyway 13.3.0、Thymeleaf、LangChain4j BOM 1.19.0、FFmpeg、MCP Streamable HTTP、Caddy。

**Spec:** `docs/specs/2026-08-27-shangan-v1-design.md`

## Global Constraints

- V1 只实现 iOS；不要创建 Android、Web 或桌面客户端。
- iOS 最低版本为 16。
- 后端只能运行一个实例，SQLite 文件必须位于本机磁盘。
- AI 只读；禁止任何写工具。
- 不使用 `langchain4j-agentic` 实验模块。
- Emby、LLM、ASR、MCP 密钥不能进入 Flutter、日志或 API 响应。
- 不使用 Redis、Kafka、向量数据库、微服务或对象存储。
- 所有日期边界按用户时区处理，数据库时间统一 UTC Epoch Milliseconds。
- 所有状态机和时间规则必须通过注入的 `java.time.Clock` 测试。
- 每个 Task 独立完成测试、评审和提交，禁止一次提交跨越多个 Task。
- Task 1–3 在应用脚手架尚未齐全时运行各 Task 明确的验证命令；从 Task 4 起，每次提交前运行 `make format && make verify`。
- 禁止用跳过测试、删除断言或放宽业务规则的方式让测试通过。

---

## File Structure Map

```text
shangan/
├── AGENTS.md
├── START_HERE.md
├── CODEX_START_PROMPT.md
├── README.md
├── Makefile
├── .editorconfig
├── .gitignore
├── .env.example
├── docs/
│   ├── specs/2026-08-27-shangan-v1-design.md
│   ├── plans/2026-08-27-shangan-v1-implementation-plan.md
│   ├── traceability/2026-08-27-shangan-v1-traceability.md
│   ├── adr/
│   ├── api/openapi.yaml
│   └── runbooks/backup-restore.md
├── apps/
│   ├── server/
│   │   ├── pom.xml
│   │   ├── mvnw
│   │   ├── mvnw.cmd
│   │   └── src/
│   │       ├── main/java/com/shangan/
│   │       ├── main/resources/
│   │       └── test/java/com/shangan/
│   └── ios/
│       ├── .fvmrc
│       ├── pubspec.yaml
│       ├── lib/
│       ├── test/
│       └── integration_test/
└── infra/
    ├── compose.yml
    ├── Caddyfile
    ├── docker/server.Dockerfile
    └── scripts/
```

## Stable Interfaces Used Across Tasks

### Server time and ID

```java
package com.shangan.common;

public interface IdGenerator {
    String nextId();
}
```

Use Spring's `java.time.Clock` bean directly.

### Current user

```java
package com.shangan.common.auth;

public record CurrentUser(String userId, String username, String role, String timezone) {}
```

### Domain event-free application boundaries

```java
package com.shangan.catalog.application;

public interface CatalogQueryService {
    CourseDetail getCourse(String userId, String courseId);
    LessonDetail getLesson(String userId, String lessonId);
}
```

```java
package com.shangan.planning.application;

public interface PlanProgressPort {
    void updateVideoWatchProgress(
            String userId, String planItemId, long completedSeconds, boolean watchCompleted);

    void updateFocusProgress(
            String userId, String planItemId, long completedSeconds, boolean completed);

    void markQuizCompleted(String userId, String planItemId);
}
```


```java
package com.shangan.planning.application;

public interface VideoTaskRequirementPort {
    boolean requiresQuiz(String mediaItemId);
}
```

```java
package com.shangan.debt.application;

public interface DebtService {
    void createDebtsForClosedPlan(String planId, DebtReason reason);
    void reconcileRepayment(String planItemId, long absoluteCompletedSeconds);
    void reconcileOpenVideoDebt(
            String userId, String mediaItemId, long absoluteVerifiedPositionSeconds);
    void settleOpenQuizDebt(String userId, String mediaItemId);
}
```

```java
package com.shangan.ai.application;

public interface AiStreamSink {
    void emit(AiStreamEvent event);
    void complete();
    void fail(String errorCode, String safeMessage);
}
```

---

### Task 1: Repository Foundation, Toolchain, and Guardrails

**Files:**
- Create: `README.md`
- Create: `Makefile`
- Create: `.editorconfig`
- Create: `.gitignore`
- Create: `.env.example`
- Create: `docs/adr/0001-ios-only-v1.md`
- Create: `docs/adr/0002-modular-monolith-sqlite.md`
- Create: `docs/adr/0003-emby-media-provider.md`
- Create: `docs/adr/0004-read-only-ai.md`

**Interfaces:**
- Consumes: none.
- Produces: root commands `make format`, `make verify`, `make server-test`, `make ios-test`.

- [ ] **Step 1: Create repository-level files and ADRs**

`README.md` must contain:

```markdown
# 上岸

V1 is an iOS-only study supervision app.

## Commands

- `make server-test`
- `make ios-test`
- `make format`
- `make verify`

Read `AGENTS.md`, the V1 spec, and the implementation plan before changing code.
```

Each ADR must contain Context, Decision, Consequences, and Rejected Alternatives matching the spec.

`.env.example` must list every variable from spec section 23 with empty or safe local values. Set `QUIZ_DEBT_ESTIMATE_SECONDS=600`; never put a real secret in this file.

- [ ] **Step 2: Create the root Makefile**

```make
.PHONY: format server-test ios-test verify

format:
    cd apps/server && ./mvnw spotless:apply
    cd apps/ios && fvm dart format lib test integration_test

server-test:
    cd apps/server && ./mvnw verify

ios-test:
    cd apps/ios && fvm flutter pub get
    cd apps/ios && fvm dart format --output=none --set-exit-if-changed lib test integration_test
    cd apps/ios && fvm flutter analyze
    cd apps/ios && fvm flutter test

verify: server-test ios-test
```

- [ ] **Step 3: Verify documentation references**

Run:

```bash
test -f AGENTS.md
test -f docs/specs/2026-08-27-shangan-v1-design.md
test -f docs/plans/2026-08-27-shangan-v1-implementation-plan.md
```

Expected: exit code 0.

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "chore: establish repository guardrails"
```

---

### Task 2: Spring Boot, SQLite, Migrations, and Common API

**Files:**
- Create: `apps/server/pom.xml`
- Create: `apps/server/mvnw`
- Create: `apps/server/mvnw.cmd`
- Create: `apps/server/.mvn/wrapper/maven-wrapper.properties`
- Create: `apps/server/src/main/java/com/shangan/ShanganApplication.java`
- Create: `apps/server/src/main/java/com/shangan/common/config/ApplicationConfiguration.java`
- Create: `apps/server/src/main/java/com/shangan/common/config/SqliteConfiguration.java`
- Create: `apps/server/src/main/java/com/shangan/common/api/ApiExceptionHandler.java`
- Create: `apps/server/src/main/java/com/shangan/common/api/BusinessException.java`
- Create: `apps/server/src/main/java/com/shangan/common/UuidIdGenerator.java`
- Create: `apps/server/src/main/resources/application.yml`
- Create: `apps/server/src/main/resources/db/migration/V001__baseline.sql`
- Test: `apps/server/src/test/java/com/shangan/common/DatabaseBootstrapTest.java`
- Test: `apps/server/src/test/java/com/shangan/common/ProblemDetailTest.java`

**Interfaces:**
- Consumes: none.
- Produces: `Clock`, `IdGenerator`, SQLite `DataSource`, RFC Problem Detail errors.

- [ ] **Step 1: Create the Maven project and wrapper**

Create the Spring Boot 4.1.1 `pom.xml`, then generate and commit Maven Wrapper. The wrapper must run with Java 21:

```bash
cd apps/server
mvn -N wrapper:wrapper
./mvnw --version
```

Expected: Maven starts with Java 21.

- [ ] **Step 2: Write the database bootstrap test**

```java
@SpringBootTest
class DatabaseBootstrapTest {

    @Autowired JdbcClient jdbc;

    @Test
    void enablesRequiredSqlitePragmasAndFlyway() {
        assertThat(jdbc.sql("PRAGMA foreign_keys").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("PRAGMA journal_mode").query(String.class).single())
                .isEqualToIgnoringCase("wal");
        assertThat(jdbc.sql("select count(*) from flyway_schema_history")
                .query(Integer.class).single()).isPositive();
    }
}
```

Configure the test to use a temporary file database, not `:memory:`.

- [ ] **Step 3: Run the test and verify failure**

Run:

```bash
cd apps/server
./mvnw -Dtest=DatabaseBootstrapTest test
```

Expected: FAIL because application and schema do not exist.

- [ ] **Step 4: Implement Spring Boot foundation**

Required dependencies:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.53.4.0</version>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
  <version>13.3.0</version>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-nc-sqlite</artifactId>
  <version>13.3.0</version>
</dependency>
```

`application.yml` must set:

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:sqlite:${DATA_DIR:./data}/study.db
    hikari:
      maximum-pool-size: 4
      connection-timeout: 5000
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

Use Flyway 13.3.0 for both `flyway-core` and `flyway-database-nc-sqlite`. At startup execute the four required PRAGMAs from the spec. Configure `com.diffplug.spotless:spotless-maven-plugin:3.10.0` for Google Java Format so the root `make format` command is available from Task 2 onward.

- [ ] **Step 5: Implement baseline schema**

`V001__baseline.sql` creates `users` and `refresh_tokens` plus indexes. Use `TEXT` UUID IDs and `INTEGER` epoch milliseconds.

- [ ] **Step 6: Add Problem Detail test and implementation**

Test:

```java
@WebMvcTest
class ProblemDetailTest {
    @Test
    void returnsStableBusinessErrorCode() throws Exception {
        // A test-only controller throws new BusinessException(
        //     HttpStatus.CONFLICT, "PLAN_ALREADY_LOCKED", "计划已锁定");
        // Assert status 409 and JSON field errorCode.
    }
}
```

`ApiExceptionHandler` maps validation and `BusinessException` to Problem Detail with `errorCode` and `requestId`.

- [ ] **Step 7: Run verification**

```bash
cd apps/server
./mvnw test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add apps/server
git commit -m "feat(server): bootstrap sqlite modular monolith"
```

---

### Task 3: Identity, App JWT, and Admin Session Authentication

**Files:**
- Create: `apps/server/src/main/java/com/shangan/identity/domain/User.java`
- Create: `apps/server/src/main/java/com/shangan/identity/application/AuthService.java`
- Create: `apps/server/src/main/java/com/shangan/identity/infrastructure/UserRepository.java`
- Create: `apps/server/src/main/java/com/shangan/identity/infrastructure/JdbcUserRepository.java`
- Create: `apps/server/src/main/java/com/shangan/identity/infrastructure/JwtService.java`
- Create: `apps/server/src/main/java/com/shangan/identity/api/AuthController.java`
- Create: `apps/server/src/main/java/com/shangan/common/auth/CurrentUserArgumentResolver.java`
- Create: `apps/server/src/main/java/com/shangan/common/config/SecurityConfiguration.java`
- Create: `apps/server/src/main/java/com/shangan/admin/AdminLoginController.java`
- Create: `apps/server/src/main/resources/templates/admin/login.html`
- Create: `apps/server/src/main/resources/db/migration/V002__identity_indexes.sql`
- Test: `apps/server/src/test/java/com/shangan/identity/AuthFlowIntegrationTest.java`
- Test: `apps/server/src/test/java/com/shangan/admin/AdminSecurityTest.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/auth/login`
  - `POST /api/v1/auth/refresh`
  - `POST /api/v1/auth/logout`
  - `GET /api/v1/me`
  - `GET/PUT /api/v1/preferences`
  - `CurrentUser`

- [ ] **Step 1: Write login and refresh integration tests**

The test must assert:

```text
valid password -> access token + refresh token
invalid password -> 401 AUTH_INVALID_CREDENTIALS
refresh token rotates -> old token is revoked
disabled user -> 403 AUTH_USER_DISABLED
```

- [ ] **Step 2: Run failing tests**

```bash
cd apps/server
./mvnw -Dtest=AuthFlowIntegrationTest test
```

Expected: FAIL because endpoints do not exist.

- [ ] **Step 3: Implement identity persistence and password hashing**

Add `spring-security-oauth2-jose` and use Spring Security's Nimbus JWT encoder/decoder. Use BCrypt strength 12.

Store only SHA-256 hash of refresh tokens.

Persist user preferences:

```text
timezone
alive_check_level
day_end_local_time
```

Defaults are `Asia/Shanghai`, `NORMAL`, and `23:59`.

Access token claims:

```json
{
  "sub": "user-id",
  "username": "alice",
  "role": "USER",
  "timezone": "Asia/Shanghai"
}
```

- [ ] **Step 4: Implement admin session security**

Rules:

```text
/api/v1/auth/** -> public
/actuator/health -> public
/admin/login -> public
/admin/** -> ADMIN session
/api/v1/** -> Bearer token
```

Enable CSRF for admin forms. Do not require CSRF for Bearer-token APIs.

- [ ] **Step 5: Implement bootstrap admin**

On first startup, if no ADMIN exists and both environment variables are present, create one:

```text
ADMIN_BOOTSTRAP_USERNAME
ADMIN_BOOTSTRAP_PASSWORD
```

Do not log the password.

- [ ] **Step 6: Run tests**

```bash
cd apps/server
./mvnw -Dtest=AuthFlowIntegrationTest,AdminSecurityTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/server
git commit -m "feat(identity): add app and admin authentication"
```

---

### Task 4: Flutter iOS Shell, Authentication, and API Client

**Files:**
- Create: `apps/ios/.fvmrc`
- Create: `apps/ios/pubspec.yaml`
- Create: `apps/ios/lib/main.dart`
- Create: `apps/ios/lib/app/bootstrap.dart`
- Create: `apps/ios/lib/app/app.dart`
- Create: `apps/ios/lib/app/router.dart`
- Create: `apps/ios/lib/core/api/api_client.dart`
- Create: `apps/ios/lib/core/api/api_exception.dart`
- Create: `apps/ios/lib/core/auth/auth_repository.dart`
- Create: `apps/ios/lib/core/auth/auth_controller.dart`
- Create: `apps/ios/lib/core/storage/token_store.dart`
- Create: `apps/ios/lib/features/auth/presentation/login_page.dart`
- Create: `apps/ios/lib/features/dashboard/presentation/app_shell.dart`
- Create: `apps/ios/lib/features/profile/data/preferences_repository.dart`
- Create: `apps/ios/lib/features/profile/presentation/settings_page.dart`
- Test: `apps/ios/test/core/auth/auth_controller_test.dart`
- Test: `apps/ios/test/features/auth/login_page_test.dart`
- Create: `apps/ios/integration_test/app_shell_smoke_test.dart`
- Create: `.github/workflows/server-verify.yml`
- Create: `.github/workflows/ios-verify.yml`

**Interfaces:**
- Consumes: Auth API from Task 3.
- Produces:
  - authenticated Dio client
  - automatic one-time refresh
  - root navigation shell.

- [ ] **Step 1: Initialize Flutter app with iOS only**

Run:

```bash
mkdir -p apps
cd apps
fvm use 3.44.7 --force
fvm flutter create --platforms=ios --org com.shangan ios
```

Set iOS deployment target to 16.

- [ ] **Step 2: Add dependencies**

Run from `apps/ios`:

```bash
fvm flutter pub add flutter_riverpod:3.0.2 go_router:17.5.0 dio:5.11.0
fvm flutter pub add flutter_secure_storage:11.0.0 video_player:2.14.0
fvm flutter pub add flutter_chat_ui:2.11.0 shared_preferences
fvm flutter pub add --dev mocktail
```

Commit the resolved `pubspec.lock`.

- [ ] **Step 3: Write failing auth controller test**

```dart
test('refreshes once and retries the original request', () async {
  final api = FakeApi()
    ..enqueueUnauthorized()
    ..enqueueRefreshSuccess()
    ..enqueueSuccess({'id': 'user-1'});
  final controller = AuthController(api: api, tokenStore: FakeTokenStore());

  final result = await controller.loadCurrentUser();

  expect(result.id, 'user-1');
  expect(api.refreshCalls, 1);
});
```

- [ ] **Step 4: Implement token storage and Dio interceptors**

Requirements:

- Access and refresh token only in Keychain.
- On 401, one request performs refresh; concurrent requests wait for it.
- A retried 401 clears tokens and routes to login.
- Never log Authorization header.

- [ ] **Step 5: Implement login page and five-tab shell**

Tabs:

```text
首页 | 学习 | AI | 数据 | 我的
```

The non-authenticated route is `/login`. The authenticated initial route is `/home`.

The My tab exposes settings for `aliveCheckLevel` and `dayEndLocalTime`, backed by:

```text
GET /api/v1/preferences
PUT /api/v1/preferences
```

- [ ] **Step 6: Add an integration-test shell**

Create `integration_test/app_shell_smoke_test.dart` so the directory exists from V1 foundation onward. It launches the app with fake authentication and asserts the five-tab shell renders; Task 16 expands this into the full flow.

- [ ] **Step 7: Add CI workflows**

`server-verify.yml` runs on Ubuntu with Java 21, installs `ffmpeg` and `sqlite3`, then executes:

```bash
make server-test
```

`ios-verify.yml` runs on macOS, installs FVM, executes `make ios-test`, then:

```bash
cd apps/ios
fvm flutter build ios --simulator --no-codesign
```

- [ ] **Step 8: Run full checks**

```bash
cd apps/ios
fvm dart format lib test
fvm flutter analyze
fvm flutter test
fvm flutter build ios --simulator --no-codesign
```

Expected: all PASS.

Run from repository root:

```bash
make verify
```

- [ ] **Step 9: Commit**

```bash
git add apps/ios .github Makefile
git commit -m "feat(ios): add authenticated application shell"
```

---

### Task 5: Emby Adapter, Course Sync, and Admin Course Management

**Files:**
- Create: `apps/server/src/main/java/com/shangan/media/emby/EmbyProperties.java`
- Create: `apps/server/src/main/java/com/shangan/media/emby/EmbyClient.java`
- Create: `apps/server/src/main/java/com/shangan/media/emby/EmbyDtos.java`
- Create: `apps/server/src/main/java/com/shangan/catalog/domain/Course.java`
- Create: `apps/server/src/main/java/com/shangan/catalog/domain/MediaItem.java`
- Create: `apps/server/src/main/java/com/shangan/catalog/application/CourseSyncService.java`
- Create: `apps/server/src/main/java/com/shangan/catalog/infrastructure/JdbcCourseRepository.java`
- Create: `apps/server/src/main/java/com/shangan/catalog/api/CatalogController.java`
- Create: `apps/server/src/main/java/com/shangan/admin/CourseAdminController.java`
- Create: `apps/server/src/main/resources/templates/admin/courses.html`
- Create: `apps/server/src/main/resources/templates/admin/course-lessons.html`
- Create: `apps/server/src/main/resources/db/migration/V003__catalog.sql`
- Test: `apps/server/src/test/java/com/shangan/catalog/CourseSyncServiceTest.java`
- Test: `apps/server/src/test/java/com/shangan/media/emby/EmbyClientContractTest.java`
- Create: `apps/ios/lib/features/catalog/data/catalog_repository.dart`
- Create: `apps/ios/lib/features/catalog/presentation/course_list_page.dart`
- Create: `apps/ios/lib/features/catalog/presentation/course_detail_page.dart`
- Test: `apps/ios/test/features/catalog/course_list_page_test.dart`

**Interfaces:**
- Produces:
  - `GET /api/v1/courses`
  - `GET /api/v1/courses/{courseId}`
  - `GET /api/v1/lessons/{lessonId}`
  - `CatalogQueryService`.

- [ ] **Step 1: Write an Emby contract test using WireMock**

Stub:

```json
{
  "Items": [
    {
      "Id": "emby-ep-1",
      "Name": "资料分析 01",
      "RunTimeTicks": 36000000000,
      "MediaType": "Video",
      "IndexNumber": 1
    }
  ]
}
```

Assert the client sends `X-Emby-Token`, never returns it, and converts ticks to milliseconds.

- [ ] **Step 2: Write course sync tests**

Assert:

```text
new episode -> inserted
existing episode title/duration -> updated
local enabled/sort_order -> preserved
removed Emby item -> marked unavailable, not deleted
second sync -> idempotent
```

- [ ] **Step 3: Run failing tests**

```bash
cd apps/server
./mvnw -Dtest=EmbyClientContractTest,CourseSyncServiceTest test
```

Expected: FAIL.

- [ ] **Step 4: Implement Emby client and catalog schema**

Never accept a per-request Emby base URL. The base URL must come only from `EmbyProperties`.

- [ ] **Step 5: Implement admin pages**

Admin can:

- create a course with name and Emby parent item ID;
- trigger sync;
- enable/disable lessons;
- reorder lessons;
- see last sync result.

- [ ] **Step 6: Implement scheduled sync**

Use a single scheduled task every 15 minutes. If Emby is unavailable, log one safe error and retain local data.

- [ ] **Step 7: Implement iOS course list and detail**

The Study tab displays enabled courses and lessons from local server snapshots. A lesson can be selected while creating a DRAFT plan item; direct learning is allowed but must still create a watch session.

- [ ] **Step 8: Run tests and commit**

```bash
make verify
git add .
git commit -m "feat(catalog): sync courses and lessons from emby"
```

---

### Task 6: Exam Goal, Progress Pressure, and Dashboard API

**Files:**
- Create: `apps/server/src/main/java/com/shangan/exam/domain/ExamGoal.java`
- Create: `apps/server/src/main/java/com/shangan/exam/application/ExamGoalService.java`
- Create: `apps/server/src/main/java/com/shangan/exam/application/ExamProgressCalculator.java`
- Create: `apps/server/src/main/java/com/shangan/exam/api/ExamController.java`
- Create: `apps/server/src/main/java/com/shangan/dashboard/DashboardController.java`
- Create: `apps/server/src/main/resources/db/migration/V004__exam_goals.sql`
- Test: `apps/server/src/test/java/com/shangan/exam/ExamProgressCalculatorTest.java`
- Test: `apps/server/src/test/java/com/shangan/dashboard/DashboardApiTest.java`
- Create: `apps/ios/lib/features/exam/data/exam_repository.dart`
- Create: `apps/ios/lib/features/exam/presentation/exam_goal_page.dart`
- Create: `apps/ios/lib/features/dashboard/data/dashboard_repository.dart`
- Create: `apps/ios/lib/features/dashboard/presentation/home_page.dart`
- Test: `apps/ios/test/features/dashboard/home_page_test.dart`

**Interfaces:**
- Produces:
  - `GET/PUT /api/v1/exam-goal`
  - `GET /api/v1/exam-progress`
  - `GET /api/v1/dashboard`.

- [ ] **Step 1: Write progress calculation tests with a fixed Clock**

Cases:

```text
exam in 63 days
target finish in 49 days
81 lessons remaining
required pace = 81 / 49
7-day pace below required -> AT_RISK
projected finish after target -> projected date returned
```

- [ ] **Step 2: Implement exam schema and calculator**

Use `LocalDate` for user-facing dates and resolve “today” with the user's timezone and injected Clock.

- [ ] **Step 3: Implement dashboard DTO**

Dashboard includes:

```json
{
  "exam": {},
  "todayPlan": {},
  "openDebtSeconds": 0,
  "studyTodaySeconds": 0,
  "answerAccuracy": 0,
  "continueLesson": null,
  "progressPressure": {}
}
```

- [ ] **Step 4: Implement iOS first-run exam goal and home page**

When no active goal exists, route to exam setup before home.

- [ ] **Step 5: Verify**

```bash
make verify
```

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "feat(exam): add countdown and progress pressure"
```

---

### Task 7: Daily Plans, Locking, Abandonment, and Debt Ledger

**Files:**
- Create: `apps/server/src/main/java/com/shangan/planning/domain/DailyPlan.java`
- Create: `apps/server/src/main/java/com/shangan/planning/domain/PlanStatus.java`
- Create: `apps/server/src/main/java/com/shangan/planning/application/DailyPlanService.java`
- Create: `apps/server/src/main/java/com/shangan/planning/application/ActiveLearningCloser.java`
- Create: `apps/server/src/main/java/com/shangan/planning/application/VideoTaskRequirementPort.java`
- Modify: `apps/server/src/main/java/com/shangan/dashboard/DashboardController.java`
- Create: `apps/server/src/main/java/com/shangan/planning/api/DailyPlanController.java`
- Create: `apps/server/src/main/java/com/shangan/debt/domain/LearningDebt.java`
- Create: `apps/server/src/main/java/com/shangan/debt/application/DefaultDebtService.java`
- Create: `apps/server/src/main/java/com/shangan/debt/api/DebtController.java`
- Create: `apps/server/src/main/resources/db/migration/V005__planning_and_debt.sql`
- Test: `apps/server/src/test/java/com/shangan/planning/DailyPlanStateMachineTest.java`
- Test: `apps/server/src/test/java/com/shangan/debt/DebtGenerationIntegrationTest.java`
- Test: `apps/server/src/test/java/com/shangan/planning/DailyPlanCloseSchedulerIntegrationTest.java`
- Create: `apps/ios/lib/features/planning/data/plan_repository.dart`
- Create: `apps/ios/lib/features/planning/presentation/plan_page.dart`
- Create: `apps/ios/lib/features/planning/presentation/lock_plan_sheet.dart`
- Create: `apps/ios/lib/features/planning/presentation/abandon_plan_sheet.dart`
- Create: `apps/ios/lib/features/debt/presentation/debt_page.dart`
- Test: `apps/ios/test/features/planning/abandon_plan_sheet_test.dart`

**Interfaces:**
- Consumes `List<ActiveLearningCloser>` and `List<VideoTaskRequirementPort>`; empty lists are valid until learning/focus/quiz modules register implementations.
- Produces plan and debt APIs from the spec.
- Produces `PlanProgressPort`, `DebtService`, `ActiveLearningCloser`, and `VideoTaskRequirementPort`.
- Extends `GET /api/v1/dashboard` with the actual current plan and open-debt totals.

- [ ] **Step 1: Write state machine tests**

```java
@Test
void lockedPlanRejectsMutation() {
    DailyPlan plan = DailyPlan.draft(...);
    plan.lock(clock.instant());
    assertThatThrownBy(() -> plan.addItem(...))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("计划已锁定");
}
```

Cover all allowed and forbidden transitions.

- [ ] **Step 2: Write idempotent debt generation and repayment tests**

Create a locked plan with one partially watched, quiz-required VIDEO item and one untouched FOCUS item. Run close twice. Assert exactly three debts (`VIDEO_WATCH`, `QUIZ`, `FOCUS`), correct remaining seconds, and no duplicates.

Create a DRAFT debt-repayment plan item, call `PlanProgressPort.updateVideoWatchProgress` repeatedly with absolute values `300`, `300`, and `420`, and assert repayment rows contain only deltas `300` and `120`.

Create a VIDEO plan item with `quiz_required=true`. Assert watch completion alone does not complete the item; `markQuizCompleted` completes it only after watch completion. Also assert the inverse ordering works.

- [ ] **Step 3: Implement transactional application services**

Create this cross-module port:

```java
package com.shangan.planning.application;

public interface ActiveLearningCloser {
    void closeForPlan(String userId, String planId, Instant closedAt);
}
```

`abandon(planId, reason)` must perform in one transaction:

```text
verify LOCKED
invoke every ActiveLearningCloser
insert plan_abandonment
set ABANDONED
create debts
```

The reporting module introduced in Task 12 reads the persisted plan, abandonment and debt rows; Task 7 must not call a not-yet-existing reporting service.

When a plan locks, query all `VideoTaskRequirementPort` implementations and snapshot `quiz_required` for each VIDEO item. In Task 7 the list is empty, so the value defaults to false; Task 11 supplies the quiz implementation.

`PlanProgressPort` stores absolute, monotonic `completed_seconds`, clamps it to `planned_seconds`, calculates the positive delta, and invokes `DebtService.reconcileRepayment` when the plan item references a debt. A VIDEO item reaches COMPLETED only when `watch_completed=true` and either `quiz_required=false` or `quiz_completed=true`. Duplicate absolute values and duplicate quiz completion calls must be idempotent.

Debt generation rules are exact: create `VIDEO_WATCH` for remaining trusted video seconds, `QUIZ` for an unmet quiz requirement using the frozen 600-second estimate, and `FOCUS` for remaining focus seconds. A `DEBT_REPAYMENT` item never creates a new debt when it closes; it only leaves the referenced debt open. Enforce uniqueness on `(source_plan_item_id, debt_type)`.

Use explicit repository methods; no controller-level transaction.

- [ ] **Step 4: Update dashboard aggregation**

Replace the Task 6 initial zero-valued plan/debt fields with current-plan status, planned/completed seconds, and the sum of OPEN/PARTIAL debt remaining seconds. Add assertions to `DashboardApiTest`.

- [ ] **Step 5: Add day-close scheduler**

Every minute, find locked plans whose local day has ended. Close them idempotently with reason `DAY_END`. `DailyPlanCloseSchedulerIntegrationTest` uses users in two IANA timezones, a fixed `Clock`, and repeated scheduler runs to prove correct boundaries and idempotency.

- [ ] **Step 6: Implement iOS plan and debt pages**

The abandon confirmation must show remaining tasks and exact added debt before enabling confirm.

- [ ] **Step 7: Verify and commit**

```bash
make verify
git add .
git commit -m "feat(planning): lock plans and carry unfinished work as debt"
```

---

### Task 8: Emby Playback Tickets and Streaming Proxy

**Files:**
- Create: `apps/server/src/main/java/com/shangan/learning/application/PlaybackTicketService.java`
- Create: `apps/server/src/main/java/com/shangan/media/emby/EmbyPlaybackClient.java`
- Create: `apps/server/src/main/java/com/shangan/media/emby/EmbyStreamProxy.java`
- Create: `apps/server/src/main/java/com/shangan/learning/api/PlaybackProxyController.java`
- Create: `apps/server/src/main/java/com/shangan/learning/api/WatchSessionController.java`
- Create: `apps/server/src/main/resources/db/migration/V006__watch_sessions.sql`
- Test: `apps/server/src/test/java/com/shangan/learning/PlaybackTicketServiceTest.java`
- Test: `apps/server/src/test/java/com/shangan/media/emby/RangeProxyIntegrationTest.java`
- Test: `apps/server/src/test/java/com/shangan/media/emby/HlsManifestRewriteTest.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/lessons/{lessonId}/watch-sessions`
  - playback proxy routes.
- Produces a `PlaybackSessionResponse` with session ID, ticket URL, trusted position, duration, heartbeat interval.

- [ ] **Step 1: Write playback ticket tests**

Assert:

```text
valid signed ticket -> accepted
expired ticket -> rejected
ticket for another user -> rejected
tampered ticket -> rejected
ticket contains no Emby API key
```

- [ ] **Step 2: Write Range proxy test**

WireMock returns:

```text
206
Content-Range: bytes 100-199/1000
Content-Length: 100
Accept-Ranges: bytes
```

Assert the controller forwards `Range: bytes=100-199` and preserves required response headers.

- [ ] **Step 3: Write HLS rewrite test**

Input manifest:

```m3u8
#EXTM3U
main.m3u8?MediaSourceId=x
```

Expected output points to:

```text
/api/v1/playback/{ticket}/proxy/main.m3u8?MediaSourceId=x
```

Reject absolute URLs whose host differs from configured Emby.

- [ ] **Step 4: Implement streaming without whole-file buffering**

Use JDK `HttpClient` with `BodyHandlers.ofInputStream()` and `StreamingResponseBody`.

Set fixed upstream host from configuration. Copy only safe headers.

- [ ] **Step 5: Implement Emby PlaybackInfo selection**

Choose direct stream when compatible; otherwise construct HLS request with H.264/AAC. Generate a unique Emby DeviceId and PlaySessionId per watch session.

- [ ] **Step 6: Verify and commit**

```bash
cd apps/server
./mvnw -Dtest=PlaybackTicketServiceTest,RangeProxyIntegrationTest,HlsManifestRewriteTest test
git add .
git commit -m "feat(media): proxy emby playback with signed tickets"
```

---

### Task 9: Trusted Watch Progress and Random Alive Checks

**Files:**
- Create: `apps/server/src/main/java/com/shangan/learning/domain/WatchProgressPolicy.java`
- Create: `apps/server/src/main/java/com/shangan/learning/application/WatchSessionService.java`
- Create: `apps/server/src/main/java/com/shangan/learning/application/WatchSessionPlanCloser.java`
- Create: `apps/server/src/main/java/com/shangan/learning/infrastructure/JdbcWatchSessionRepository.java`
- Create: `apps/server/src/main/java/com/shangan/learning/infrastructure/JdbcVideoProgressRepository.java`
- Create: `apps/server/src/main/java/com/shangan/learning/api/WatchHeartbeatRequest.java`
- Create: `apps/server/src/main/java/com/shangan/learning/api/WatchHeartbeatResponse.java`
- Create: `apps/server/src/main/resources/db/migration/V007__video_progress_and_alive_checks.sql`
- Test: `apps/server/src/test/java/com/shangan/learning/WatchProgressPolicyTest.java`
- Test: `apps/server/src/test/java/com/shangan/learning/WatchSessionIntegrationTest.java`
- Test: `apps/server/src/test/java/com/shangan/learning/AliveCheckSchedulerTest.java`

**Interfaces:**
- Consumes `PlanProgressPort` and implements `ActiveLearningCloser`.
- Produces heartbeat, alive-check, stop APIs.
- Calls `PlanProgressPort.updateVideoWatchProgress` after every accepted heartbeat and when the session stops.
- Calls `DebtService.reconcileOpenVideoDebt` so trusted direct viewing can repay existing video debt even without a plan item.

- [ ] **Step 1: Write policy tests**

Given heartbeat interval 10 seconds:

```text
position grows 10s in 10s -> accept
position jumps 120s in 10s -> reject jump
duplicate sequence -> no double count
paused -> no count
background -> no count
alive check pending -> no count
position within verified region during replay -> no new max progress
```

Use exact expected `verifiedWatchMs` and `maxVerifiedPositionMs`.

- [ ] **Step 2: Implement acceptance formula**

For each heartbeat:

```text
elapsed = clamp(now - lastHeartbeat, 0, 15s)
positionDelta = currentPosition - lastPosition
allowedForwardDelta = elapsed × 1.25 + 3000ms
acceptedForwardDelta = clamp(positionDelta, 0, allowedForwardDelta)
countedWatch = min(elapsed, acceptedForwardDelta)
```

Only apply when `playing && foreground && !aliveCheckPending`.

After accepting a heartbeat, update the linked plan item with absolute progress derived from `maxVerifiedPositionMs`, not total replay time, and reconcile any OPEN/PARTIAL `VIDEO_WATCH` debt for the same user and media item. A rejected jump returns trusted position and `seekAllowed=false`; it does not terminate the session.

- [ ] **Step 3: Implement completion threshold**

```java
long tolerance = Math.min(30_000L, Math.round(durationMs * 0.02));
boolean completed = maxVerifiedPositionMs >= durationMs - tolerance;
```

- [ ] **Step 4: Implement plan-close integration**

`WatchSessionPlanCloser` implements `ActiveLearningCloser`. It closes every RUNNING watch session attached to the plan, persists final trusted progress, and updates the linked plan item before debt generation continues. Add an integration test proving a partially watched video produces debt only for the unverified remainder.

- [ ] **Step 5: Implement alive-check scheduling**

Use a cryptographically strong random duration inside the user's `alive_check_level` range. Persist the due threshold so app restart cannot reset it. When the level is OFF, do not schedule checks.

- [ ] **Step 6: Run tests**

```bash
cd apps/server
./mvnw -Dtest=WatchProgressPolicyTest,WatchSessionIntegrationTest,AliveCheckSchedulerTest test
```

- [ ] **Step 7: Commit**

```bash
git add apps/server
git commit -m "feat(learning): verify watch progress and require alive checks"
```

---

### Task 10: iOS Learning Player

**Files:**
- Create: `apps/ios/lib/features/player/domain/learning_player_state.dart`
- Create: `apps/ios/lib/features/player/data/watch_repository.dart`
- Create: `apps/ios/lib/features/player/presentation/learning_player_controller.dart`
- Create: `apps/ios/lib/features/player/presentation/learning_player_page.dart`
- Create: `apps/ios/lib/features/player/presentation/verified_progress_bar.dart`
- Create: `apps/ios/lib/features/player/presentation/alive_check_dialog.dart`
- Test: `apps/ios/test/features/player/learning_player_controller_test.dart`
- Test: `apps/ios/test/features/player/verified_progress_bar_test.dart`
- Test: `apps/ios/test/features/player/alive_check_dialog_test.dart`

**Interfaces:**
- Consumes Task 8 and Task 9 APIs.
- Produces reusable iOS learning player and watch session lifecycle.

- [ ] **Step 1: Define a player adapter**

```dart
abstract interface class PlayerAdapter {
  Stream<Duration> get positionStream;
  Future<void> open(Uri uri, {Map<String, String> headers = const {}});
  Future<void> play();
  Future<void> pause();
  Future<void> seek(Duration position);
  Future<void> dispose();
}
```

`VideoPlayerAdapter` wraps `video_player`. Tests use `FakePlayerAdapter`.

- [ ] **Step 2: Write controller tests**

Assert:

```text
foreground playing -> heartbeat
background -> pause immediately
server seek rejection -> seek back to trusted position
three heartbeat failures -> pause and show network error
aliveCheckRequired -> pause and show modal
alive check pass -> resume only after explicit user action
```

- [ ] **Step 3: Implement seek guard**

The progress bar exposes the entire duration visually but clamps drag target to `maxVerifiedPosition`. It must never call `seek` with a larger value.

- [ ] **Step 4: Implement lifecycle and heartbeat**

Use `WidgetsBindingObserver`. Send sequence numbers. Stop session in `dispose` and on explicit back navigation.

- [ ] **Step 5: Run tests and physical-device smoke checklist**

Automated:

```bash
cd apps/ios
fvm flutter analyze
fvm flutter test
```

Manual on iPhone:

```text
play HLS
pause/resume
background pauses
back-seek works
forward-seek blocked
alive check blocks playback
network loss pauses
```

- [ ] **Step 6: Commit**

```bash
git add apps/ios
git commit -m "feat(ios): add trusted emby learning player"
```

---

### Task 11: Questions, Attempts, and Admin Question Editor

**Files:**
- Create: `apps/server/src/main/java/com/shangan/quiz/domain/Question.java`
- Create: `apps/server/src/main/java/com/shangan/quiz/application/QuizService.java`
- Create: `apps/server/src/main/java/com/shangan/quiz/application/QuizRequirementAdapter.java`
- Create: `apps/server/src/main/java/com/shangan/quiz/api/QuizController.java`
- Create: `apps/server/src/main/java/com/shangan/quiz/infrastructure/JdbcQuestionRepository.java`
- Create: `apps/server/src/main/java/com/shangan/admin/QuestionAdminController.java`
- Create: `apps/server/src/main/resources/templates/admin/questions.html`
- Create: `apps/server/src/main/resources/templates/admin/question-form.html`
- Create: `apps/server/src/main/resources/db/migration/V008__quiz.sql`
- Test: `apps/server/src/test/java/com/shangan/quiz/QuizUnlockIntegrationTest.java`
- Test: `apps/server/src/test/java/com/shangan/quiz/QuizScoringTest.java`
- Test: `apps/server/src/test/java/com/shangan/quiz/QuizAttemptIntegrationTest.java`
- Create: `apps/ios/lib/features/quiz/data/quiz_repository.dart`
- Create: `apps/ios/lib/features/quiz/presentation/quiz_page.dart`
- Create: `apps/ios/lib/features/quiz/presentation/quiz_result_page.dart`
- Test: `apps/ios/test/features/quiz/quiz_page_test.dart`

**Interfaces:**
- Consumes video completion from Task 9.
- Produces quiz APIs and marks VIDEO plan item complete after a full attempt.

- [ ] **Step 1: Write unlock and scoring tests**

Assert:

```text
unfinished video -> QUIZ_LOCKED
completed video -> questions returned without correct flags
single choice -> exact scoring
true/false -> exact scoring
disabled question -> excluded
submission missing answer -> validation error
complete attempt -> plan item updated and matching QUIZ debt settled exactly once
```

- [ ] **Step 2: Implement schema and service**

`QuizRequirementAdapter.requiresQuiz(mediaItemId)` returns true when at least one enabled question exists. Plan locking snapshots this answer.

Do not send `correct` flags or explanations before submission. `POST .../quiz-attempts` accepts optional `planItemId`; after a complete attempt, validate ownership/media match, call `PlanProgressPort.markQuizCompleted` when present, and call `DebtService.settleOpenQuizDebt` for the current user/media item. Both operations must be idempotent.

- [ ] **Step 3: Implement admin editor**

Validation:

- single choice has at least 2 options and exactly 1 correct;
- true/false has exactly 2 options and exactly 1 correct;
- content is non-empty;
- ordering is deterministic.

- [ ] **Step 4: Implement iOS quiz flow**

When video completes and questions exist, show “开始课后答题”. The result page shows score and explanations.

- [ ] **Step 5: Verify and commit**

```bash
make verify
git add .
git commit -m "feat(quiz): add post-video assessments"
```

---

### Task 12: Focus Timer, Daily Report, Weekly Report, and Judgment

**Files:**
- Create: `apps/server/src/main/java/com/shangan/focus/domain/FocusSession.java`
- Create: `apps/server/src/main/java/com/shangan/focus/application/FocusSessionService.java`
- Create: `apps/server/src/main/java/com/shangan/focus/application/FocusSessionPlanCloser.java`
- Create: `apps/server/src/main/java/com/shangan/focus/api/FocusSessionController.java`
- Create: `apps/server/src/main/java/com/shangan/reporting/application/DailyReportService.java`
- Create: `apps/server/src/main/java/com/shangan/reporting/application/WeeklyReportService.java`
- Create: `apps/server/src/main/java/com/shangan/reporting/application/JudgmentRenderer.java`
- Create: `apps/server/src/main/java/com/shangan/reporting/application/DailyReportGenerationScheduler.java`
- Create: `apps/server/src/main/java/com/shangan/reporting/api/ReportController.java`
- Create: `apps/server/src/main/resources/db/migration/V009__focus_and_reports.sql`
- Test: `apps/server/src/test/java/com/shangan/focus/FocusSessionStateMachineTest.java`
- Test: `apps/server/src/test/java/com/shangan/reporting/DailyReportAggregationTest.java`
- Test: `apps/server/src/test/java/com/shangan/reporting/WeeklyReportAggregationTest.java`
- Test: `apps/server/src/test/java/com/shangan/reporting/DailyReportGenerationSchedulerTest.java`
- Test: `apps/server/src/test/java/com/shangan/reporting/JudgmentRendererTest.java`
- Create: `apps/ios/lib/features/focus/presentation/focus_timer_page.dart`
- Create: `apps/ios/lib/features/reporting/presentation/daily_report_page.dart`
- Create: `apps/ios/lib/features/reporting/presentation/weekly_report_page.dart`
- Test: `apps/ios/test/features/focus/focus_timer_page_test.dart`

**Interfaces:**
- Consumes `PlanProgressPort` and implements `ActiveLearningCloser`.
- Produces focus and report APIs.
- A scheduler materializes reports for plans in terminal states `COMPLETED`, `ABANDONED`, or `CLOSED_WITH_DEBT`; report GET endpoints can also regenerate deterministically on demand.

- [ ] **Step 1: Write focus state tests**

Transitions:

```text
RUNNING -> PAUSED -> RUNNING -> FINISHED
RUNNING -> CANCELLED
FINISHED/CANCELLED -> no further transition
```

Actual seconds derive from server timestamps, not client totals. On pause, finish, cancel, and plan close, update the linked plan item through `PlanProgressPort.updateFocusProgress`.

`FocusSessionPlanCloser` cancels active sessions at the supplied close time, preserves valid elapsed seconds, and persists final plan progress before debt generation.

- [ ] **Step 2: Write report aggregation tests**

Use fixtures that include watch sessions, focus sessions, quiz attempts, debts, alive checks and abandonment.

Assert exact totals and completion rate.

- [ ] **Step 3: Write deterministic judgment tests**

Given fixed report values, assert exact selected template and interpolated numbers. Do not call AI.

- [ ] **Step 4: Implement deterministic report materialization**

Every minute, find plans in `COMPLETED`, `ABANDONED`, or `CLOSED_WITH_DEBT` whose report snapshot is stale and upsert the current local-day report. Also upsert the previous local day once after midnight so late session closure is reflected. Re-running the job must produce the same totals and judgment text.

- [ ] **Step 5: Implement iOS timer and report pages**

Timer uses `serverNow` and session timestamps. On app resume, recalculate from timestamps.

- [ ] **Step 6: Verify and commit**

```bash
make verify
git add .
git commit -m "feat(reporting): add focus timing and study judgments"
```

---

### Task 13: Video Transcription, FTS5, and Hierarchical Summaries

**Files:**
- Create: `apps/server/src/main/java/com/shangan/ai/transcript/TranscriptionProvider.java`
- Create: `apps/server/src/main/java/com/shangan/ai/transcript/OpenAiCompatibleTranscriptionProvider.java`
- Create: `apps/server/src/main/java/com/shangan/ai/transcript/FfmpegAudioExtractor.java`
- Create: `apps/server/src/main/java/com/shangan/ai/transcript/TranscriptionJobService.java`
- Create: `apps/server/src/main/java/com/shangan/ai/transcript/VideoSummaryService.java`
- Create: `apps/server/src/main/java/com/shangan/ai/transcript/TranscriptSearchRepository.java`
- Create: `apps/server/src/main/java/com/shangan/admin/TranscriptionAdminController.java`
- Create: `apps/server/src/main/resources/templates/admin/transcriptions.html`
- Create: `apps/server/src/main/resources/db/migration/V010__transcripts_and_fts.sql`
- Test: `apps/server/src/test/java/com/shangan/ai/transcript/FfmpegAudioExtractorTest.java`
- Test: `apps/server/src/test/java/com/shangan/ai/transcript/TranscriptionJobServiceTest.java`
- Test: `apps/server/src/test/java/com/shangan/ai/transcript/TranscriptSearchRepositoryTest.java`
- Test: `apps/server/src/test/java/com/shangan/ai/transcript/TranscriptionPipelineIntegrationTest.java`

**Interfaces:**
- Produces `READY` video AI context and `search(mediaItemId, query, limit)`.

- [ ] **Step 1: Create FTS5 migration and test**

Test inserts two transcript segments and asserts a keyword query returns the correct timestamped segment.

Migration creates sync triggers for insert, update and delete.

- [ ] **Step 2: Write FFmpeg process test**

Use a small generated test audio fixture. Assert output is mono 16kHz and split into deterministic chunks.

The process runner must:

```text
timeout after configured duration
capture bounded stderr
delete temp files
reject non-zero exit
```

- [ ] **Step 3: Implement ASR provider contract test**

WireMock verifies multipart upload and returns time-stamped segments. The API key comes from configuration and is redacted in logs.

- [ ] **Step 4: Implement idempotent job state machine**

A retry deletes previous partial segments in one transaction before inserting replacement results.

Only one job may be active globally.

- [ ] **Step 5: Implement hierarchical summary**

- section size: 5 to 10 minutes based on transcript boundaries;
- global summary input: all section summaries;
- persist model name and generation time;
- mark READY only after FTS and both summary levels exist.

- [ ] **Step 6: Implement admin trigger and retry**

Admin sees status, attempts, timestamps, safe error and retry action.

- [ ] **Step 7: Verify and commit**

```bash
cd apps/server
./mvnw -Dtest='*Transcription*,*TranscriptSearch*,*Ffmpeg*' test
git add .
git commit -m "feat(ai): transcribe and summarize emby lessons"
```

---

### Task 14: Read-Only AI, MCP Web Search, Chat Persistence, and SSE

**Files:**
- Create: `apps/server/src/main/java/com/shangan/ai/config/AiConfiguration.java`
- Create: `apps/server/src/main/java/com/shangan/ai/domain/AiConversation.java`
- Create: `apps/server/src/main/java/com/shangan/ai/application/ReadOnlyStudyTools.java`
- Create: `apps/server/src/main/java/com/shangan/ai/application/VideoContextBuilder.java`
- Create: `apps/server/src/main/java/com/shangan/ai/application/AiConversationService.java`
- Create: `apps/server/src/main/java/com/shangan/ai/infrastructure/McpToolAllowlist.java`
- Create: `apps/server/src/main/java/com/shangan/ai/infrastructure/JdbcAiConversationRepository.java`
- Create: `apps/server/src/main/java/com/shangan/ai/api/AiConversationController.java`
- Create: `apps/server/src/main/resources/db/migration/V011__ai_conversations.sql`
- Test: `apps/server/src/test/java/com/shangan/ai/ReadOnlyToolBoundaryTest.java`
- Test: `apps/server/src/test/java/com/shangan/ai/McpToolAllowlistTest.java`
- Test: `apps/server/src/test/java/com/shangan/ai/VideoContextBuilderTest.java`
- Test: `apps/server/src/test/java/com/shangan/ai/AiSseIntegrationTest.java`

**Interfaces:**
- Consumes reporting, planning, debt, exam and transcript read APIs.
- Produces AI conversation and SSE APIs.

- [ ] **Step 1: Add LangChain4j dependencies using its BOM**

Import `dev.langchain4j:langchain4j-bom:1.19.0`, then add:

```xml
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j</artifactId>
</dependency>
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-open-ai</artifactId>
</dependency>
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-mcp</artifactId>
</dependency>
```

The BOM resolves compatible core and integration versions. Instantiate `OpenAiStreamingChatModel`, MCP clients, `ToolProvider`, memory provider and AI Services programmatically in `AiConfiguration`. Do not add Spring auto-registration starters or the experimental Agentic module.

- [ ] **Step 2: Write reflection-based read-only boundary test**

The test inspects all methods exposed as AI tools and asserts:

```text
name starts with get_ or search_
return type is read DTO
declaring class has no Repository write dependency
tool name is in explicit allowlist
```

Maintain the exact allowed internal tool set from the spec.

- [ ] **Step 3: Write MCP allowlist tests**

Given discovered tools:

```text
web_search
web_extract
filesystem_write
shell_exec
```

and configured allowlist `web_search,web_extract`, assert only the first two are exposed to the model.

- [ ] **Step 4: Write video context builder tests**

Assert the context includes:

- global summary;
- current position ±3 minutes;
- top 8 FTS hits;
- relevant section summaries;
- no content from another video;
- a delimiter marking transcript as untrusted content.

- [ ] **Step 5: Implement chat persistence and SSE**

SSE event order:

```text
message_start
zero or more tool_status
zero or more delta
zero or more citation
message_end
```

On failure after partial output:

```text
error
```

Persist the user message before model call and assistant message only after completion. Persist partial assistant text with status FAILED if streaming breaks.

- [ ] **Step 6: Enforce concurrency and limits**

- one active stream per user;
- max user message length 8,000 characters;
- MCP timeout 20 seconds;
- MCP response truncation 50,000 characters;
- conversation history bounded by configured context budget;
- no full prompts in logs.

- [ ] **Step 7: Run tests**

```bash
cd apps/server
./mvnw -Dtest='*Ai*,*Mcp*,*VideoContext*' test
```

- [ ] **Step 8: Commit**

```bash
git add apps/server
git commit -m "feat(ai): add read-only chat with mcp web search"
```

---

### Task 15: iOS AI Tab and Video AI Bottom Sheet

**Files:**
- Create: `apps/ios/lib/features/ai_chat/domain/chat_models.dart`
- Create: `apps/ios/lib/features/ai_chat/data/ai_chat_repository.dart`
- Create: `apps/ios/lib/features/ai_chat/data/sse_parser.dart`
- Create: `apps/ios/lib/features/ai_chat/presentation/ai_chat_controller.dart`
- Create: `apps/ios/lib/features/ai_chat/presentation/ai_tab_page.dart`
- Create: `apps/ios/lib/features/ai_chat/presentation/video_ai_sheet.dart`
- Create: `apps/ios/lib/features/ai_chat/presentation/citation_list.dart`
- Test: `apps/ios/test/features/ai_chat/sse_parser_test.dart`
- Test: `apps/ios/test/features/ai_chat/ai_chat_controller_test.dart`
- Test: `apps/ios/test/features/ai_chat/video_ai_sheet_test.dart`

**Interfaces:**
- Consumes Task 14 AI APIs and Task 13 AI status.
- Produces two AI entry points with shared data/controller layer.

- [ ] **Step 1: Write SSE parser tests**

Feed fragmented chunks such as:

```text
event: delta
data: {"text":"你"}

event: delta
data: {"text":"好"}
```

Assert incremental text is `你好`, event boundaries survive arbitrary network chunking, and malformed events produce a safe parser error.

- [ ] **Step 2: Implement shared chat repository**

Methods:

```dart
Future<Conversation> createConversation(ChatScope scope, {String? lessonId});
Future<List<ChatMessage>> loadMessages(String conversationId);
Stream<AiStreamEvent> sendMessage(String conversationId, String text);
```

- [ ] **Step 3: Implement AI Tab**

Use `flutter_chat_ui` for message presentation. Render source citations under assistant messages. Disable send while one stream is active.

- [ ] **Step 4: Implement video AI Bottom Sheet**

Pass lesson ID and current playback position. If transcript status is not READY:

```text
show transcript processing state
allow regular questions
do not claim to answer from video
```

- [ ] **Step 5: Verify**

```bash
cd apps/ios
fvm flutter analyze
fvm flutter test
```

- [ ] **Step 6: Commit**

```bash
git add apps/ios
git commit -m "feat(ios): add general and video ai chat"
```

---

### Task 16: Admin Completion, OpenAPI, Operations, Backup, and End-to-End Acceptance

**Files:**
- Create: `apps/server/src/main/java/com/shangan/admin/UserAdminController.java`
- Create: `apps/server/src/main/resources/templates/admin/users.html`
- Create: `apps/server/src/main/resources/templates/admin/health.html`
- Create: `docs/api/openapi.yaml`
- Create: `docs/runbooks/backup-restore.md`
- Create: `docs/runbooks/v1-physical-device-acceptance.md`
- Create: `infra/docker/server.Dockerfile`
- Create: `infra/compose.yml`
- Create: `infra/Caddyfile`
- Create: `infra/scripts/backup.sh`
- Create: `infra/scripts/restore.sh`
- Test: `apps/server/src/test/java/com/shangan/api/OpenApiContractTest.java`
- Test: `apps/server/src/test/java/com/shangan/acceptance/FullLearningFlowAcceptanceTest.java`
- Test: `apps/server/src/test/java/com/shangan/acceptance/AbandonAndDebtAcceptanceTest.java`
- Test: `apps/server/src/test/java/com/shangan/acceptance/AiReadOnlyAcceptanceTest.java`
- Test: `infra/scripts/backup_restore_smoke_test.sh`
- Test: `apps/ios/integration_test/core_flow_test.dart`

**Interfaces:**
- Consumes all earlier tasks.
- Produces deployable V1 release candidate.

- [ ] **Step 1: Complete minimal admin pages**

Admin can create/disable users and see:

```text
database path
database size
WAL size
Emby status
last course sync
active transcription job
LLM/ASR/MCP configured status without secrets
```

- [ ] **Step 2: Generate and freeze OpenAPI**

Use `org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.0` during `mvn verify`. Copy generated YAML to `docs/api/openapi.yaml` and add a test that fails when committed and generated contracts differ.

- [ ] **Step 3: Write backup and restore scripts**

`backup.sh`:

```bash
set -euo pipefail
sqlite3 "${DATA_DIR}/study.db" ".backup '${BACKUP_DIR}/study-${STAMP}.db'"
sqlite3 "${BACKUP_DIR}/study-${STAMP}.db" "PRAGMA integrity_check;"
```

Fail unless output is exactly `ok`. `backup_restore_smoke_test.sh` creates a temporary database, backs it up while the service database is open, restores into a new path, and asserts schema version plus representative row counts.

`restore.sh` requires service stopped, validates backup, archives current database, and restores the selected file.

- [ ] **Step 4: Build container**

The server image must contain:

```text
JRE 21
ffmpeg
sqlite3
non-root user
/data volume
```

No secrets in image layers.

- [ ] **Step 5: Write end-to-end server acceptance tests**

Cover the five V1 acceptance scenarios in the spec with fake Emby, ASR, LLM and MCP servers.

- [ ] **Step 6: Write iOS integration flow**

Use fakes for media playback and AI in CI. Test:

```text
login
set exam
create and lock plan
open lesson
simulate completion
submit quiz
abandon remaining item
see debt
open AI tab and receive stream
```

- [ ] **Step 7: Run complete verification**

```bash
make format
make verify
docker compose -f infra/compose.yml config
docker build -f infra/docker/server.Dockerfile -t shangan-server:local .
```

Expected: all PASS.

- [ ] **Step 8: Perform physical iPhone acceptance**

Record results in `docs/runbooks/v1-physical-device-acceptance.md` with:

```text
device model
iOS version
app build
Emby version
direct play result
HLS result
background pause
seek guard
alive check
quiz
focus timer
AI Tab
video AI
24-hour resume
```

- [ ] **Step 9: Commit the release candidate**

```bash
git add .
git commit -m "chore: harden and package v1 release candidate"
```

- [ ] **Step 10: Create release candidate tag**

```bash
git status --short
make verify
git tag -a v0.1.0-rc1 -m "上岸 V1 release candidate 1"
```

`git status --short` must be empty before tagging.

---

### Task 17: Configurable Flutter Server Address and Local Development Runtime

**Files:**
- Create: `apps/ios/lib/core/config/server_configuration.dart`
- Create: `apps/ios/lib/core/config/server_configuration_store.dart`
- Create: `apps/ios/lib/core/config/server_health_checker.dart`
- Create: `apps/ios/lib/app/application_bootstrap.dart`
- Create: `apps/ios/lib/features/auth/presentation/server_settings_page.dart`
- Modify: `apps/ios/lib/main.dart`
- Modify: `apps/ios/lib/app/bootstrap.dart`
- Modify: `apps/ios/lib/features/auth/presentation/login_page.dart`
- Test: `apps/ios/test/core/config/server_configuration_test.dart`
- Test: `apps/ios/test/core/config/server_configuration_store_test.dart`
- Test: `apps/ios/test/app/application_bootstrap_test.dart`
- Test: `apps/ios/test/features/auth/server_settings_page_test.dart`
- Update: `apps/ios/README.md`

**Interfaces:**
- Consumes public `/actuator/health`.
- Produces a persisted, validated server origin and a root dependency rebuild callback.
- Does not change the OpenAPI business contract.

- [ ] **Step 1: Write failing configuration tests**

Assert:

```text
http/https origin -> accepted and trailing slash removed
missing host or unsupported scheme -> rejected
userinfo/query/fragment/API subpath -> rejected
saved address -> overrides API_BASE_URL
empty saved address -> falls back to API_BASE_URL
```

- [ ] **Step 2: Implement server configuration and persistence**

Use `SharedPreferences` only for the non-sensitive address. Do not persist Token or business data in this store.

- [ ] **Step 3: Write health-check and switching tests**

Assert:

```text
2xx + status UP -> address may be saved
timeout/non-2xx/status not UP -> old address remains active
successful switch -> Access/Refresh Token cleared
successful switch -> all repositories use the new origin
successful switch -> authentication state is unauthenticated
```

- [ ] **Step 4: Implement root dependency rebuild**

Extract dependency construction from the one-shot `bootstrap()` function into a stateful root component. The root owns the current configuration, Token Store, AuthController and Repository graph. After a successful switch, dispose the old graph and rebuild all providers from the new address.

- [ ] **Step 5: Implement login-page server settings**

Add a top-right action on the login page. The configuration page validates input, performs unauthenticated `/actuator/health`, saves only after success, and displays Chinese error messages. The login page displays the current host and port.

- [ ] **Step 6: Start and verify the local server**

Start Spring Boot with non-committed development secrets and a workspace-local SQLite directory. Verify:

```bash
curl http://127.0.0.1:8080/actuator/health
```

Expected: HTTP 200 and `status=UP`.

- [ ] **Step 7: Run verification**

```bash
cd apps/ios
fvm flutter test test/core/config test/app/application_bootstrap_test.dart test/features/auth/server_settings_page_test.dart
cd ../..
make format
make verify
```

- [ ] **Step 8: Commit**

```bash
git add .
git commit -m "feat(ios): allow configuring the server address"
```

---

## Cross-Task Review Gates

After Tasks 1–4:

```text
Repository builds
Authentication works
iOS shell logs in
No feature code has bypassed the API boundary
```

After Tasks 5–7:

```text
Emby course sync works
Exam dashboard works
Locked plan cannot mutate
Abandon and normal day-end create idempotent debts
```

After Tasks 8–10:

```text
Emby key never reaches client
Range and HLS proxy work
Forward seek is blocked
Heartbeat cannot inflate progress
Alive checks block playback
```

After Tasks 11–12:

```text
Video completion unlocks quiz
Focus timer survives app lifecycle
Daily and weekly totals reconcile with raw rows
Judgment uses rules, not AI
```

After Tasks 13–15:

```text
Transcript pipeline is retryable
FTS5 and summary context work
AI has no write tools
MCP is allowlisted
General and video chat stream on iOS
```

After Task 16:

```text
Backup restore proven
Physical iPhone flow proven
All acceptance tests pass
Release candidate is reproducible
```

After Task 17:

```text
Server address is configurable before login
Unhealthy targets cannot replace the active address
Switching server clears old credentials
All Flutter repositories use one consistent origin
Local Spring Boot health endpoint is reachable
```

---

## Codex Execution Rules

Codex must:

1. Read `AGENTS.md`, the spec, and this plan before editing.
2. Work on one Task at a time.
3. Create a dedicated branch or worktree.
4. Start each Task with the failing test described here.
5. Run the narrow test first, then the full module test.
6. Review the diff for scope expansion and secret leakage.
7. Commit with the provided commit subject.
8. Stop at each Cross-Task Review Gate and report evidence.
9. Never silently change a frozen product rule.
10. Record any unavoidable deviation as a new ADR before implementation.

---

## Final Self-Review Checklist

- [ ] Every V1 spec section maps to at least one Task.
- [ ] No Task implements Android, PC Web, multi-agent or vector search.
- [ ] Every state transition has tests.
- [ ] All external services have contract tests.
- [ ] Playback proxy never buffers a full video.
- [ ] AI tool set is provably read-only.
- [ ] Secrets remain server-side.
- [ ] SQLite backup and restore have been executed, not merely documented.
- [ ] Physical iPhone acceptance is recorded.
- [ ] `make verify` passes from repository root.
