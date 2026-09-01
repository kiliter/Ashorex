# 上岸 V1 Implementation Plan

> **执行要求：** 按 Task 顺序单线程实施，不使用多智能体。步骤使用 checkbox（`- [ ]`）跟踪。

**Goal:** 构建一款面向 iPhone、iPad 和 Android 的 Flutter 学习监督 App，完成 Emby 视频学习、可信观看、今日作战单编排、模拟考试、自动日终结算、欠债、答题、独立专注、报表，以及课程自动转写、摘要和 AI 题目草稿闭环。

**Architecture:** 单仓库包含 Flutter 移动端与 Spring Boot 模块化单体。服务端以 SQLite 为业务真相，Emby 提供媒体与音频流；课程全文和 Markdown 摘要可由管理员导入，也可由持久化串行任务调用 OpenAI-compatible ASR/LLM 生成；AI 题目只进入待审核草稿，管理员发布后才进入正式题库。Flutter 只负责交互、播放器、心跳和只读内容查询。

**Tech Stack:** Flutter 3.44.7 stable、Dart 3.12.x、flutter_riverpod 3.0.2、go_router 17.5.0、Dio 5.11.0、video_player 2.14.0、Java 21、Spring Boot 4.1.1、Spring MVC、JdbcClient、sqlite-jdbc 3.53.4.0、SQLite WAL、Flyway 13.3.0、Thymeleaf、LangChain4j OpenAI 1.19.0、Caddy。

**Spec:** `docs/specs/2026-08-27-shangan-v1-design.md`

## Global Constraints

- V1 移动端覆盖 iPhone、iPad 和 Android；不创建 PC Web 或桌面客户端。
- iOS 最低版本为 16。
- Android 最低版本为 API 24。
- 后端只能运行一个实例，SQLite 文件必须位于本机磁盘。
- 服务端只允许课时转写、摘要和 AI 题目草稿；不包含 AI Chat、智能体、MCP、联网搜索或 AI 业务写能力。
- Emby 密钥不能进入 Flutter、日志或业务 API 响应；仅允许存在于服务端存储和 ADMIN 配置页面。
- 不使用 Redis、Kafka、向量数据库、微服务或对象存储。
- 所有日期边界按用户时区处理，数据库时间统一 UTC Epoch Milliseconds。
- 所有状态机和时间规则必须通过注入的 `java.time.Clock` 做纯逻辑测试。
- 服务端自动化测试不得启动 SQLite、Flyway 或其他真实数据库；历史 Task 中的数据库集成测试要求统一由 Task 23 替代。
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

V1 is a Flutter study supervision app for iPhone, iPad, and Android.

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
- Create: `apps/server/src/main/resources/db/migration/V018__persistent_admin_sessions.sql`
- Create: `docs/adr/0009-persistent-admin-session.md`
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

管理后台使用 Spring Session JDBC 将认证上下文持久化到 SQLite，Flyway 负责 Session 表结构，七天无访问后失效。测试必须证明登录后 Session 已写入数据库；App API 的安全链继续保持 `STATELESS`。

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

- [ ] **Step 1: Initialize Flutter app for iPhone, iPad, and Android**

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
fvm flutter pub add shared_preferences
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

- [ ] **Step 5: Implement login page and initial shell**

Tabs:

```text
首页 | 学习 | 数据 | 我的
```

The non-authenticated route is `/login`. The authenticated initial route is `/home`.

The My tab exposes learning preferences for `aliveCheckEnabled`, `aliveCheckIntervalPercent` and `dayEndLocalTime`, backed by:

```text
GET /api/v1/preferences
PUT /api/v1/preferences
```

Every user may use a slider to update `aliveCheckIntervalPercent` in the range 1–50; the default is 50. The same page may disable alive checks without changing the saved percentage.

- [ ] **Step 6: Add an integration-test shell**

Create `integration_test/app_shell_smoke_test.dart` so the directory exists from V1 foundation onward. It launches the app with fake authentication and asserts the four-tab shell renders; Task 16 expands this into the full flow.

- [ ] **Step 7: Add CI workflows**

`server-verify.yml` runs on Ubuntu with Java 21, installs `sqlite3`, then executes:

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

> **历史 Task，计划锁定和手动开摆由 Task 21 替代。** 已有欠债、日终调度和历史数据读取继续复用。

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
allowedForwardDelta = elapsed × playbackSpeed × 1.25 + 3000ms
acceptedForwardDelta = clamp(positionDelta, 0, allowedForwardDelta)
countedWatch = min(elapsed, acceptedForwardDelta / playbackSpeed)
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

> **部分被 Task 22 替代。** 可信心跳、验活和 Seek Guard 继续保留；画面内控制层、横屏全屏、摘要和复习模式由 Task 22 实现。

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

> **部分被 Task 21 和 Task 22 替代。** 专注状态机与报表聚合继续保留；专注不再绑定作战单，手动开摆改为自动日终结果，报表日期导航改用选择器。

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

> **历史 Task，已被 Task 19 替代。** 已有实现由 Task 19 迁移可复用数据后删除，不得继续扩展。

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

> **历史 Task，已被 Task 19 替代。** 已有接口、依赖和数据表由 Task 19 删除。

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

> **历史 Task，已被 Task 19 替代。** 已有 Flutter AI 入口与基础设施由 Task 19 删除。

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
- Test: `apps/server/src/test/java/com/shangan/acceptance/LessonStudyContentAcceptanceTest.java`
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
lesson study content count
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

Fail unless output is exactly `ok`. 根据 ADR-0014，备份恢复改为发布前人工演练，不再提供数据库运行时 Smoke Test。

`restore.sh` requires service stopped, validates backup, archives current database, and restores the selected file.

- [ ] **Step 4: Build container**

The server image must contain:

```text
JRE 21
sqlite3
non-root user
/data volume
```

No secrets in image layers.

- [ ] **Step 5: Write end-to-end server acceptance tests**

Cover the V1 acceptance scenarios in the spec with a fake Emby server and temporary SQLite database.

- [ ] **Step 6: Write iOS integration flow**

Use fakes for media playback in CI. Test:

```text
login
set exam
create and lock plan
open lesson
simulate completion
submit quiz
abandon remaining item
see debt
read imported lesson study content
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
four-tab shell
no player AI entry
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
- Create: `apps/ios/lib/core/config/server_configuration_controller.dart`
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
curl http://127.0.0.1:18080/actuator/health
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

### Task 18：Web 管理后台运行时外部服务配置

> **部分被 Task 19 替代。** 仅保留 Emby 运行时配置；LLM、ASR 和 MCP 配置由 Task 19 删除。

**文件：**
- 参考：`docs/adr/0006-admin-runtime-integration-settings.md`
- 新建：`apps/server/src/main/java/com/shangan/common/integration/RuntimeIntegrationSettings.java`
- 新建：`apps/server/src/main/java/com/shangan/common/integration/IntegrationSettingsProvider.java`
- 新建：`apps/server/src/main/java/com/shangan/common/integration/RuntimeIntegrationSettingsRepository.java`
- 新建：`apps/server/src/main/java/com/shangan/common/integration/JdbcRuntimeIntegrationSettingsRepository.java`
- 新建：`apps/server/src/main/java/com/shangan/common/integration/RuntimeIntegrationSettingsService.java`
- 新建：`apps/server/src/main/java/com/shangan/admin/IntegrationSettingsAdminController.java`
- 新建：`apps/server/src/main/resources/templates/admin/integration-settings.html`
- 新建：`apps/server/src/main/resources/db/migration/V012__runtime_integration_settings.sql`
- 修改：`apps/server/src/main/java/com/shangan/ai/config/AiConfiguration.java`
- 新建：`apps/server/src/main/java/com/shangan/ai/application/RuntimeAiChatEngine.java`
- 修改：`apps/server/src/main/java/com/shangan/ai/transcript/OpenAiCompatibleTranscriptionProvider.java`
- 修改：`apps/server/src/main/java/com/shangan/ai/transcript/OpenAiCompatibleSummaryProvider.java`
- 修改：`apps/server/src/main/java/com/shangan/media/emby/EmbyProperties.java`
- 修改：当前在启动时固定捕获配置的 Emby 适配器
- 修改：`apps/server/src/main/java/com/shangan/admin/OperationsHealthService.java`
- 修改：`apps/server/src/main/resources/templates/admin/fragments.html`
- 测试：`apps/server/src/test/java/com/shangan/common/integration/RuntimeIntegrationSettingsServiceTest.java`
- 测试：`apps/server/src/test/java/com/shangan/admin/IntegrationSettingsAdminTest.java`
- 测试：`apps/server/src/test/java/com/shangan/ai/RuntimeAiConfigurationIntegrationTest.java`
- 测试：`apps/server/src/test/java/com/shangan/ai/transcript/RuntimeTranscriptionConfigurationTest.java`
- 测试：`apps/server/src/test/java/com/shangan/media/emby/RuntimeEmbyConfigurationTest.java`

**接口：**
- 提供 ADMIN Session 页面 `GET/POST /admin/settings/integrations`。
- 为服务端外部适配器提供不可变的当前配置快照。
- 不新增或修改 `/api/v1` 契约。

- [ ] **步骤 1：编写配置来源优先级和持久化失败测试**

断言：

```text
没有数据库记录 -> 使用整份环境变量快照
存在数据库记录 -> 使用整份数据库快照
数据库字段为空 -> 保持为空，不逐字段回退环境变量
保存成功 -> 数据库和当前快照同时更新
保存失败 -> 当前快照保持不变
```

- [ ] **步骤 2：增加追加式迁移和配置仓库**

创建固定主键为 `default` 的单行模型，迁移时不预插入记录。`updated_at` 使用 UTC Epoch Milliseconds。Repository 方法仅负责读取和替换该行。

- [ ] **步骤 3：实现校验和运行时原子刷新**

校验固定 HTTP/HTTPS URL，不允许用户名、密码、Query 或 Fragment，但允许提供商固定路径。上下文 Token 数限制为 1,024–1,000,000，Temperature 为 0–2，LLM 超时为 1–600 秒，ASR 超时为 1–1,800 秒，MCP 超时为 1–120 秒。应用服务在一个短事务中写入整份配置，再原子发布不可变快照。不完整的服务配置仍可保存，但状态显示“未配置”。

- [ ] **步骤 4：实现 ADMIN 配置页面**

增加四个移动端优先配置区和一个统一保存操作。密码字段渲染当前值并提供显示/隐藏按钮。要求 ADMIN Session 和 CSRF，返回 `Cache-Control: no-store`，且不记录提交值。

- [ ] **步骤 5：改造外部适配器读取配置快照**

新的 AI 流、ASR 切片、摘要、MCP 连接和 Emby 请求在操作开始时读取当前快照。保留 Emby 和 MCP 固定目标校验。运行状态页面改为根据当前快照计算配置状态。

- [ ] **步骤 6：运行窄测试和完整验证**

```bash
cd apps/server
./mvnw -Dtest='*RuntimeIntegrationSettings*,IntegrationSettingsAdminTest,AdminSecurityTest' test
cd ../..
make format
make verify
```

- [ ] **步骤 7：检查并提交**

检查范围、CSRF、缓存响应头、日志、错误和密钥扫描，然后提交：

```bash
git add .
git commit -m "feat(admin): configure runtime integrations"
```

---

### Task 19：移除服务端 AI 并增加课程学习内容导入

> **部分被 Task 20 替代。** Task 19 删除通用 AI Runtime 的结果继续保留；Task 20 仅恢复课程内容转写、摘要和题目草稿能力。

**前置文档：**

- `docs/adr/0007-remove-server-ai-runtime.md`
- `docs/superpowers/specs/2026-08-31-remove-server-ai-and-import-study-content-design.md`

**主要文件：**

- 新建：`apps/server/src/main/resources/db/migration/V013__replace_ai_with_lesson_study_contents.sql`
- 新建：`apps/server/src/main/java/com/shangan/catalog/domain/LessonStudyContent.java`
- 新建：`apps/server/src/main/java/com/shangan/catalog/application/LessonStudyContentImportService.java`
- 新建：`apps/server/src/main/java/com/shangan/catalog/infrastructure/LessonStudyContentZipParser.java`
- 新建：`apps/server/src/main/java/com/shangan/catalog/infrastructure/JdbcLessonStudyContentRepository.java`
- 修改：`apps/server/src/main/java/com/shangan/catalog/api/CatalogController.java`
- 修改：`apps/server/src/main/java/com/shangan/catalog/application/CatalogQueryService.java`
- 修改：`apps/server/src/main/java/com/shangan/admin/CourseAdminController.java`
- 修改：`apps/server/src/main/resources/templates/admin/course-lessons.html`
- 修改：`apps/server/src/main/java/com/shangan/common/integration/*`
- 删除：`apps/server/src/main/java/com/shangan/ai/**`
- 删除：`apps/server/src/main/java/com/shangan/admin/TranscriptionAdminController.java`
- 删除：`apps/server/src/main/resources/templates/admin/transcriptions.html`
- 删除：`apps/ios/lib/features/ai_chat/**`
- 修改：Flutter Shell、路由、播放器和 `pubspec.yaml`
- 修改：Maven、Docker、Compose、配置、CI、OpenAPI 和运行文档

**接口：**

- ADMIN：在课程课时页上传 ZIP 并批量导入。
- App API：`GET /api/v1/lessons/{lessonId}/study-content`。
- 运行时配置：只保留 Emby。

- [ ] **步骤 1：先写 V013 迁移测试**

从 V012 结构准备历史转写、全局摘要、聊天和四类运行时配置，执行迁移后精确断言：

```text
历史分段按 segment_index 合并为 full_text
历史全局摘要进入 summary_markdown
ai_messages、ai_conversations、转写、FTS 和旧摘要表已删除
runtime_integration_settings 只保留 Emby 字段和值
```

- [ ] **步骤 2：实现追加式 V013**

先迁移可复用数据，再删除旧表和触发器。不得修改 V010～V012。迁移 SQL 使用中文注释解释历史数据兼容策略。

- [ ] **步骤 3：先写 ZIP 解析和原子导入测试**

覆盖正确包、缺少文件、重复 Emby Item ID、非法 UTF-8、空内容、路径穿越、50 MiB 压缩包与 100 MiB 解压文本上限、非当前课程课时、任意一项失败零写入以及重复导入覆盖。测试使用固定 `Clock`，不使用任意睡眠。

- [ ] **步骤 4：实现最小导入链路和后台入口**

ZIP 解析器不落盘，只生成完全校验后的命令对象；应用服务校验课程归属并拥有单个事务；Repository 批量 Upsert。课程课时页显示上传入口、内容状态、更新时间和明确错误。所有新增类和非显然方法增加中文注释。

- [ ] **步骤 5：先写只读 API 测试并实现接口**

覆盖鉴权、正确 DTO、未导入错误码和无效课时。成功响应包含 `lessonId`、`fullText`、`summaryMarkdown`、`updatedAt`，并同步 `docs/api/openapi.yaml`。

- [ ] **步骤 6：删除服务端 AI Runtime**

删除 `ai` 模块、转写后台、AI 专属测试、LangChain4j/LLM/ASR/MCP/FFmpeg 依赖与配置。收缩运行时配置模型、后台表单和健康页面，只保留 Emby；更新现有配置测试，确保密钥仍不泄露。

- [ ] **步骤 7：删除 Flutter AI 并改为四 Tab**

删除 `ai_chat` Feature、AI 路由、播放器按钮、SSE 和聊天依赖。更新 Shell 与播放器 Widget 测试，断言四个 Tab 且无 AI 入口。

- [ ] **步骤 8：清理部署与文档残留**

从 Docker、Compose、`.env.example`、`application.yml`、CI 和运行手册移除 LLM、ASR、MCP、FFmpeg。使用 `rg` 复核残留只出现在历史迁移、已替代 ADR 和 Task 19 的删除说明中。

- [ ] **步骤 9：运行窄测试和完整验证**

```bash
cd apps/server
./mvnw -Dtest='*LessonStudyContent*,*Migration*,IntegrationSettingsAdminTest' test
cd ../ios
fvm flutter analyze
fvm flutter test
cd ../..
make format
make verify
```

- [ ] **步骤 10：检查并提交**

检查 API 契约、迁移顺序、事务边界、敏感信息、临时 ZIP、依赖锁文件和范围。按用户已明确的工作方式直接在 `main` 提交：

```bash
git add .
git commit -m "feat(catalog): import lesson study contents"
```

---

### Task 20：课程自动转写、摘要、AI 出题与内容任务后台

**前置文档：**

- `docs/adr/0008-server-course-content-generation.md`
- `docs/superpowers/specs/2026-08-31-course-content-generation-design.md`

**主要文件：**

- 新建：`apps/server/src/main/resources/db/migration/V014__content_generation.sql`
- 新建：`apps/server/src/main/java/com/shangan/ai/content/domain/*`
- 新建：`apps/server/src/main/java/com/shangan/ai/content/application/*`
- 新建：`apps/server/src/main/java/com/shangan/ai/content/infrastructure/*`
- 新建：`apps/server/src/main/java/com/shangan/ai/content/api/*`
- 新建：`apps/server/src/main/java/com/shangan/media/emby/EmbyAudioClient.java`
- 新建：`apps/server/src/main/java/com/shangan/admin/ContentJobAdminController.java`
- 新建：`apps/server/src/main/java/com/shangan/admin/QuizDraftAdminController.java`
- 新建：`apps/server/src/main/resources/templates/admin/content-jobs.html`
- 新建：`apps/server/src/main/resources/templates/admin/content-job-detail.html`
- 新建：`apps/server/src/main/resources/templates/admin/quiz-drafts.html`
- 修改：运行时配置模型、仓库、服务、表单和配置页面
- 修改：课程、课时、运行状态页面以及共享样式和导航
- 修改：`lesson_study_contents` Repository、查询 DTO 和 OpenAPI
- 修改：`pom.xml`、`.env.example`、`application.yml` 和部署配置
- 测试：Emby 音频、ASR NDJSON、LLM 分层处理、任务状态机、模型目录缓存、题目草稿和批量发布集成测试

**接口：**

- ADMIN：课时“AI 一下”和多课时批量完整工作流，内容详情单阶段重做，任务列表、详情、重试，题目草稿批量通过、驳回和删除，OpenRouter 模型目录刷新。
- App API：扩展 `GET /api/v1/lessons/{lessonId}/study-content` 表达全文与摘要部分就绪。
- 不增加 Flutter 写接口、聊天接口、MCP 或智能体。

- [ ] **步骤 1：更新依赖并先写 V014 迁移测试**

使用稳定版 `dev.langchain4j:langchain4j-open-ai:1.19.0`。不得使用当前仍为预发布版本的 Spring Boot 4 Starter，不引入 Agentic、MCP、Embedding 或向量库依赖。

V014 集成测试从 V013 数据库升级并精确断言：

```text
已有全文和摘要完整迁移
lesson_study_contents 允许两项独立就绪
内容任务、日志、模型目录和题目草稿表存在
运行时配置恢复 ASR、LLM、OpenRouter 和默认关闭的自动补全字段
外键、唯一约束和幂等发布约束有效
```

- [ ] **步骤 2：实现 Emby 音频流和 ASR Provider**

先使用 WireMock 覆盖 PlaybackInfo、`/Audio/{Id}/stream.mp3`、音频流中断、ASR multipart 和 NDJSON 分片。实现必须：

```text
请求 16 kHz / 单声道 / 64 kbps MP3
边接收边写临时文件，不下载视频
ASR stream=true，不发送 mlx-audio 专属 chunk_duration
只按顺序拼接每行 text，不重复拼接 accumulated，并清理 vLLM Qwen3-ASR 返回的 `<asr_text>` 标记
流内 error、空文本、非 2xx 和超时失败
成功、失败、取消和超时后都删除临时音频
```

- [ ] **步骤 3：实现 OpenRouter 模型目录缓存和运行时配置**

固定调用 `https://openrouter.ai/api/v1/models`，解析模型 ID、显示名、上下文、最大输出、Tokenizer 和支持参数。刷新使用短事务 Upsert，缺失旧模型标记 inactive；失败保留旧缓存。配置页支持缓存搜索选择和 CPA 自定义模型手工兜底。

运行时配置使用现有原子快照模式。任务开始时冻结 Base URL、模型 ID、上下文、输出上限和思考等级；页面保存后的新配置只影响新任务。任何密钥不得进入业务 API、任务日志和错误响应。

配置页提供 ASR 与 LLM 连通性测试。ASR 使用服务端内置的“你好”MP3，测试后删除临时文件；LLM 只发送最小提示词。两个测试均使用已保存配置，结果不得包含密钥。

- [ ] **步骤 4：实现共用上下文预算与递归分层处理器**

先写极小上下文窗口和超长文本测试，证明摘要和出题都不会构造超过预算的输入。处理器按段落、句子边界切片，按预算分组归并，结果仍超预算时递归处理。

使用：

```text
inputBudget = contextLength - maxCompletionTokens - 2048
```

无法可靠复现上游 Tokenizer 时使用保守字符估算，不声称精确 Token 数；记录上游实际 usage。不得把完整 Prompt 或正文写日志。

- [ ] **步骤 5：实现持久化全局串行 Worker**

状态：

```text
TRANSCRIBE：QUEUED → FETCHING_AUDIO → TRANSCRIBING → READY / FAILED
SUMMARIZE：QUEUED → SUMMARIZING → READY / FAILED
GENERATE_QUIZ：QUEUED → GENERATING_QUIZ → READY_FOR_REVIEW / FAILED
```

同课时同类型禁止重复未完成任务。批量任务按课程和课时排序创建。外部调用期间不持有事务；状态和日志使用短事务。服务重启将遗留执行态标记 `FAILED/SERVER_RESTARTED`。失败任务不自动重新消费，管理员可手动重试。

ASR 外部调用使用独立单线程池，摘要和出题共用独立 LLM 单线程池。全局 Worker 必须同步等待当前调用完成后再消费下一条任务，不能因此产生跨任务并发。两个线程池每 30 秒打印一次存活日志，包含 `IDLE/WAITING/RUNNING`、当前任务 ID、阶段、活跃线程数和排队数，不得包含正文、Prompt、请求参数或密钥。

- [ ] **步骤 6：实现摘要与 AI 题目草稿**

摘要输出固定中文 Markdown，只整理视频全文中明确讲到的内容，分段和最终汇总阶段均禁止联想、推断、评价、补充外部知识或给出学习建议。AI 出题默认 4 道单选和 1 道判断，创建任务时允许 1～20 题。支持结构化输出的模型优先使用 JSON Schema，否则使用严格 JSON；允许同一任务内一次格式修复。

校验：

```text
题型合法
单选题至少 2 个选项且唯一正确答案
判断题固定 2 个选项且唯一正确答案
题干、选项和中文解析非空
草稿属于当前课时
```

生成结果只能写草稿表，不能直接调用正式题目 Repository。

- [ ] **步骤 7：实现草稿审核和课程级批量发布**

课时题目页可编辑和删除单题。课程草稿页可选择多个草稿执行批量通过、驳回或删除。批量通过由应用服务在事务外校验全部草稿，再在一个短事务中追加正式题目和选项并标记草稿已发布；任一草稿无效整批不写入，重复提交不重复建题，已有正式题目不删除、不覆盖。驳回保留草稿内容，整份删除只允许未发布草稿。

- [ ] **步骤 8：复刻高保真后台并接入真实数据**

严格以 `/Users/zhangjialin/Downloads/shangan-admin-prototype.html` 为视觉和交互基准，完成高密度桌面布局。课时列表只保留“AI 一下”，支持勾选多个课时批量执行，单阶段重做放入内容详情；增加内容任务导航、题目草稿批量审核、运行状态耗时和服务配置，不引入单独前端框架。

内容任务列表和详情使用每 2 秒一次的局部 JSON 轮询，不重新渲染完整页面；标签页重新可见时立即刷新，单次失败继续重试，Session 失效或响应不是 JSON 时跳转登录页。所有面向管理员显示的任务类型、任务状态、日志阶段、草稿状态、账号角色和内容就绪状态统一使用中文，筛选与状态判断仍使用原始枚举值。

- [ ] **步骤 9：实现默认关闭的定时补全**

定时扫描代码存在，但 `enabled=false`。开启后只为缺失全文或摘要的课时创建任务，不覆盖已有内容，不自动生成题目草稿，并继续进入同一全局串行队列。重复扫描必须幂等。

- [ ] **步骤 10：更新只读 API、OpenAPI 和验收测试**

接口返回全文、摘要各自状态、可空内容和更新时间。两项都缺失时继续返回 `LESSON_STUDY_CONTENT_NOT_FOUND`。覆盖鉴权、部分就绪、完整就绪和无密钥泄露，同步 `docs/api/openapi.yaml`。

- [ ] **步骤 11：验证并提交**

运行内容模块窄测试、服务端验证和必要的 Flutter 契约测试：

```bash
cd apps/server
./mvnw -Dtest='*ContentGeneration*,*EmbyAudio*,*OpenRouter*,*QuizDraft*,*LessonStudyContent*' test
cd ../..
make format
make verify
```

检查临时音频、密钥、完整 Prompt、调试响应和范围后，在用户指定的 `main` 分支提交：

```bash
git add .
git commit -m "feat(ai): generate lesson contents and quiz drafts"
```

### Task 21：今日作战单、模拟考试、复习审计与自动日终结果

**前置文档：**

- `docs/adr/0011-mutable-battle-order-and-day-outcome.md`
- `CONTEXT.md`

**主要文件：**

- 新建：`apps/server/src/main/resources/db/migration/V019__battle_orders_and_mock_exams.sql`
- 修改：`planning`、`debt`、`focus`、`learning`、`reporting`、`catalog` Feature
- 新建：`apps/server/src/main/java/com/shangan/planning/application/BattleOrderService.java`
- 新建：`apps/server/src/main/java/com/shangan/focus/application/MockExamPresetService.java`
- 新建：`apps/server/src/main/java/com/shangan/focus/application/MockExamService.java`
- 新建：模拟考试预置、会话、附件和复习审计 Repository/Controller
- 修改：`docs/api/openapi.yaml`
- 测试：作战单快照、版本冲突、修订审计、模拟考试、复习事件、日终结果和附件安全测试

**接口：**

- `GET/PUT /api/v1/plans/{date}`
- `GET/POST/PUT/DELETE /api/v1/mock-exam-presets[/{id}]`
- `POST /api/v1/mock-exams/{planItemId}/start`
- `POST /api/v1/mock-exams/{sessionId}/submit-early`
- `POST /api/v1/mock-exams/{sessionId}/attachments`
- 模拟考试会话和附件鉴权读取接口

- [ ] **步骤 1：先写作战单完整快照失败测试**

覆盖首次保存、修改未开始项目、删除后不产债、不可修改项目保护、版本冲突、同日课时去重、已学课时转复习快捷入口、整单校验失败零写入和修订审计。

- [ ] **步骤 2：追加 V019 并迁移历史计划**

将未结束 `LOCKED` 计划迁移为 `ACTIVE`，保留历史终态与 `plan_abandonments` 只读数据。追加版本、修订、模拟考试预置/会话/附件和复习事件结构；不得修改 V001～V018。

- [ ] **步骤 3：实现作战单原子保存**

`PUT` 接受 `expectedVersion` 和完整项目列表。应用服务在事务中校验不可修改项目、课时状态、考试预置快照和重复项，并按课程及课时固有顺序重排视频与复习快捷入口，再完成差异保存、版本递增及修订审计。客户端 `sortOrder` 不能改变课时固有顺序；`REVIEW_SHORTCUT` 不进入完成率或欠债计算。

- [ ] **步骤 4：实现考试预置和模拟考试状态机**

预置按用户隔离并支持排序。V022 为已有用户幂等补齐行测 120 分钟、申论 180 分钟和大作文 180 分钟，新用户创建事务通过显式初始化接口补齐同样数据。模拟考试截止时间使用注入 `Clock`，切后台不暂停；自然到时或提前交卷进入 `AWAITING_UPLOAD`，至少一个合法附件后完成。测试必须覆盖非法转换、重复提交和所有权。

- [ ] **步骤 5：实现安全附件存储与备份**

文件名由服务端生成，路径固定在数据目录下，校验文件签名、扩展名、大小、数量和 SHA-256；拒绝路径穿越和越权。数据库事务失败时清理临时文件，备份恢复脚本同时处理附件目录并校验清单。

- [ ] **步骤 6：简化专注并记录复习审计**

专注 API 不再接收或更新 `planItemId`。只有 `REVIEW_SHORTCUT` 关联会话的第一次有效心跳写一条复习事件；不累计复习完成率、欠债或有效学习时长。

- [ ] **步骤 7：替换手动开摆和日终结算**

删除新的 abandon 写入口。日终幂等产生 `COMPLETED`、`CLOSED_WITH_DEBT`、`FREE_STUDY` 或 `SLACKED` 结果；服务重启补算遗漏日期。报表列出复习审计，但不把它加入学习时长。

- [ ] **步骤 8：更新 OpenAPI、运行窄测试和完整验证**

```bash
cd apps/server
./mvnw -Dtest='*BattleOrder*,*MockExam*,*DayOutcome*,*ReviewEvent*' test
cd ../..
make format
make verify
```

- [ ] **步骤 9：检查并提交**

```bash
git add CONTEXT.md docs/adr/0011-mutable-battle-order-and-day-outcome.md docs/api/openapi.yaml apps/server apps/ios
git commit -m "feat(planning): replace locked plans with battle orders"
```

### Task 22：iOS 作战单编排与学习体验修复

**主要文件：**

- 修改：Flutter `planning`、`catalog`、`player`、`focus`、`reporting`、`auth` 和 `dashboard` Feature
- 新建：作战单编排页、模拟考试预置设置、模拟考试执行与照片上传页面
- 测试：对应 Controller、Parser 和关键确认流程 Widget 测试

- [ ] **步骤 1：先写失败的 Widget 和 Controller 测试**

覆盖作战单完整保存、考试预置选择、复习快捷入口、非作战单播放提醒、课时进度与摘要按钮、播放器控制层和全屏、启动超时恢复页、首页小工具菜单以及日报/周报日期选择器。

- [ ] **步骤 2：实现作战单编排区**

首页无作战单时显示“制定今日作战单”，已有作战单时显示“修改作战单”。编排页集中选择课时和考试预置，支持排序、删除和重新选择未开始模拟考试，只有“保存作战单”一次写操作；课程详情移除加入按钮。

- [ ] **步骤 3：实现课程状态、摘要与播放提醒**

课时列表使用服务端可信进度显示未学习、百分比或已学习；摘要可用时显示小眼睛并按需弹出 Markdown。非作战单播放先提示，继续观看保留可信记录但不完成作战单。

- [ ] **步骤 4：实现播放器画面内控制和横屏全屏**

控制层包含可信进度、时间、快退/播放暂停/快进、倍速和全屏，约 3 秒自动隐藏。心跳携带当前倍速，服务端按倍速校验内容位置但按真实时间累计学习时长。全屏复用同一 Controller/会话并正确恢复方向和系统 UI。播放器下方仅在摘要就绪且非空时显示摘要。

- [ ] **步骤 5：实现首页小工具、我的菜单和模拟考试 UI**

首页右滑或点击“小工具”打开专注入口。“模拟考试预置”作为“我的”页面独立菜单管理考试名称与时长，不放在学习偏好页；作战单选择项再次点击可取消，课时严格按视频固有顺序显示且不提供手动调序；模拟考试页面按服务端截止时间显示，支持提前交卷和最多 9 张试卷照片上传。所有用户的学习偏好使用 `1%～50%` 滑杆设置验活进度间隔，默认 `50%`，并可独立关闭验活。

- [ ] **步骤 6：实现启动容错和报表日期选择**

恢复登录 8 秒超时后显示可操作恢复页，网络失败不删除 Token。日报、周报移除左右箭头并使用日期选择器和回到今天/本周。

- [ ] **步骤 7：视觉复核、测试和真机检查**

使用现有轻色 iOS 设计令牌，状态同时使用文字与图形，所有关键点击区至少 44pt。运行 Flutter format/analyze/test、模拟器构建，并在物理 iPhone 验证真实播放和照片权限。

```bash
cd apps/ios
fvm dart format lib test integration_test
fvm flutter analyze
fvm flutter test
fvm flutter build ios --simulator --no-codesign
cd ../..
make verify
```

### Task 23：统一移动端工具链并移除数据库运行时测试

**前置文档：**

- `docs/adr/0014-mobile-platforms-and-logic-only-tests.md`

**主要文件：**

- 修改：`AGENTS.md`、设计规范、实施计划和追踪矩阵
- 修改：`apps/ios/.fvmrc`、`pubspec.yaml`、`pubspec.lock`
- 修改：移动端和服务端 GitHub Actions
- 删除：所有启动 SQLite、Flyway 或真实数据库的服务端测试
- 新建：不连接数据库的日终结果业务逻辑测试

- [ ] **步骤 1：统一 Flutter 与 Dart 基线**

固定 Flutter 3.44.7 stable 和其配套的 Dart 3.12.x，将项目 SDK 约束设为 `>=3.12.0 <4.0.0`。使用干净 SDK 重新解析锁文件，并输出 CI 实际版本，防止本机 FVM 缓存污染掩盖版本漂移。

- [ ] **步骤 2：确认移动端依赖与平台范围**

保留已验证兼容 Flutter 3.44.7、Dart 3.12 的直接依赖。iOS 最低版本保持 16；Android 使用 compileSdk 37，最低运行版本为 API 24；iPad 复用 iOS 工程。移动端 CI 构建无签名 IPA 和 Android APK。

- [ ] **步骤 3：删除数据库运行时测试**

删除所有直接使用 `JdbcClient`、SQLite JDBC URL、Flyway、真实 `DataSource` 或完整 Spring Boot 数据库上下文的测试和专用 Fixture。不得通过禁用注解保留这些测试。

- [ ] **步骤 4：保留并补充纯逻辑测试**

保留领域状态机、策略、解析器、WireMock 外部协议和不连接数据库的 Controller 切片测试。把日终分类改写为使用 Fake `DayOutcomeRepository`、固定 `Clock` 和 Mock 应用边界的纯逻辑测试，精确覆盖开摆、自由学习和复习审计不计有效学习。

- [ ] **步骤 5：更新 CI 与验证命令**

服务端 CI 不安装 SQLite 工具，只执行编译、格式和逻辑测试。备份恢复改为人工发布检查，不再由 `make verify` 自动执行。运行：

```bash
make format
make server-test
make ios-test
cd apps/ios && fvm flutter build ios --simulator --no-codesign
cd apps/ios && fvm flutter build apk --debug
```

- [ ] **步骤 6：审查并提交**

检查没有真实数据库测试残留、没有依赖预发布版本、没有密钥或调试产物，并提交一次可评审变更。

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
Historical gate superseded by Task 19
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

Task 18 之后：

```text
ADMIN 可在一个页面配置 Emby；其余外部配置由 Task 19 删除
保存后无需重启，新调用使用最新配置
环境变量继续作为初始回退来源
Flutter、业务 API、日志和错误不泄露 Emby 密钥
```

Task 19 之后：

```text
服务端不包含 AI、ASR、MCP、自动转写或聊天 Runtime
Flutter 只有首页、学习、数据和我的四个 Tab，播放器无 AI 入口
课程 ZIP 导入全包原子，重复上传覆盖旧内容
课程学习内容只读接口与 OpenAPI 一致
V013 保留历史全文和全局摘要，并删除废弃表
```

Task 20 之后：

```text
服务端仅恢复课程转写、摘要和 AI 题目草稿能力，无聊天、智能体或 MCP
Emby 只传输音频，ASR NDJSON text 正确拼接且临时文件可靠删除
长视频摘要和出题使用上下文预算与递归分层处理
OpenRouter 模型名称和上下文可刷新、缓存并供 CPA 模型配置选择
任务持久化、全局串行，定时补全默认关闭且只补缺失内容
AI 题目只能进入草稿，课程级批量发布原子且幂等
后台严格复刻用户提供的高保真 HTML 风格
```

Task 21 之后：

```text
作战单通过完整快照和版本号原子保存，未开始项目可删除且不再产债
模拟考试预置、服务端倒计时、试卷附件和恢复流程可用
复习快捷入口只记录一次审计事件，不参与进度、时长、完成率或欠债
专注不再绑定作战单，手动开摆入口消失，日终结果可幂等补算
```

Task 22 之后：

```text
播放器控制位于画面内，横屏全屏复用观看会话，摘要按条件显示
课时列表显示可信进度并按需打开摘要，课程详情不再直接加入计划
已登录启动在服务不可达时进入恢复页，不无限转圈
日报和周报可通过日期选择器直接跳转
```

Task 23 之后：

```text
Flutter 3.44.7、Dart 3.12.x、pubspec 和 CI 版本一致
移动端依赖覆盖 iPhone、iPad 和 Android
服务端自动化测试不启动 SQLite、Flyway 或真实数据库
日终分类等核心规则由纯逻辑测试覆盖
```

---

## Codex Execution Rules

Codex must:

1. Read `AGENTS.md`, the spec, and this plan before editing.
2. Work on one Task at a time.
3. 默认创建独立分支或 worktree；Task 19 按用户明确要求直接在 `main` 开发。
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
- [ ] No Task implements PC Web, multi-agent or vector search.
- [ ] Every state transition has不连接数据库的逻辑测试。
- [ ] All external services have contract tests.
- [ ] Playback proxy never buffers a full video.
- [ ] 服务端只存在课时转写、摘要和 AI 题目草稿能力，不存在聊天、智能体、MCP 或 AI 业务写入口。
- [ ] 课程学习内容导入全包原子，读取接口只读。
- [ ] 长视频摘要和出题不会超过模型上下文预算，AI 题目批量发布原子且幂等。
- [ ] Secrets remain server-side.
- [ ] SQLite backup and restore have been人工执行并记录，不纳入自动化测试。
- [ ] Physical iPhone、iPad 和 Android acceptance is recorded.
- [ ] `make verify` passes from repository root.
