# 上岸 V2 实施计划

- 文档状态：待人工批准（批准后作为唯一实施授权）
- 依据：`docs/specs/2026-09-07-shangan-v2-design.md`、ADR-0025 ~ ADR-0032
- UI 依据：`docs/prototypes/shangan-v2-prototype.html`（原型与规范的 6 处矛盾已按 **ADR-0035** 收敛：播放页无睡眠定时、课程详情必须先加入今日 Todo 才能播放、无待办池、督学报告无诊断与导出、热力图时长只有 3 档）
- 追踪矩阵：`docs/traceability/2026-09-07-shangan-v2-traceability.md`

## 执行规则

> **测试策略临时调整（2026-09-07 用户决定）**
>
> 为了快速迭代，V2 首轮实施**不写单元测试**：直接完成全部代码，做一次全量编译并修复问题，由用户在本地验证行为；确认无误后再统一补齐测试（新增 **T36**）。
>
> 这与 `AGENTS.md` 的「先写失败测试 → 实现 → 跑窄测试」流程相冲突。冲突已向用户说明并由用户明确授权，授权范围限定为「V2 首轮实施」，不构成后续可以跳过测试的先例。各 Task 中列出的窄测试命令与测试用例清单**保留不删**，作为 T36 的实施依据。
>
> 首轮实施期间，每个 Task 的验证门槛降级为：**编译通过 + 静态分析通过**；涉及 Bean 构造、配置绑定、迁移或启动配置的 Task，仍必须做真实 ApplicationContext 启动 Smoke Test 并确认 `/actuator/health` 为 `UP`。

1. 首轮实施允许连续推进多个 Task，不在每个 Task 之间停顿；标记「评审门禁」的 Task 仍需汇报。
2. 每个 Task 的流程：读 Task 的文件与接口 → 实现最小完整行为 → 编译与静态分析 → 检查 diff 范围。
3. 全量 `make verify` 由 GitHub CI 执行；本地只做编译与静态分析。
4. 涉及 Spring Bean 构造、配置绑定、数据库迁移或启动配置的 Task，必须做真实 ApplicationContext 启动 Smoke Test。
5. 任何与 Spec 冲突的发现都要停止实现、说明冲突、提出最小 Spec 变更并更新 ADR，获批后再继续。
6. 测试补齐见 T36；在 T36 完成前，本计划中所有「窄测试」小节视为待办清单而非已完成事项。

## 阶段总览

| 阶段 | Task | 内容 |
|---|---|---|
| 0 基线重建 | T01 ~ T02 | 按白名单重建代码基线、重建数据库 baseline |
| 1 服务端核心 | T03 ~ T16 | 身份督学、目标、课程库、Todo、进度、催办、统计、归档删除 |
| 2 管理后台 | T17 ~ T20 | 概览、催办、课程与同步、督学关系与归档区 |
| 3 移动端学习端 | T21 ~ T30 | 主题路由、首页、视图、添加、播放、专注、学习、数据、我的、心跳催办 |
| 4 督学端与收尾 | T31 ~ T35 | 督学端、OpenAPI、文档清理、真机与备份验收 |
| 5 测试补齐 | T36 | 按各 Task 的测试清单统一补齐服务端与 Flutter 测试 |

---

## 阶段 0：拆除与基线

### T01 重建代码基线（评审门禁）

**目标**：按 ADR-0032 的保留白名单一次性清空 V1 业务层，让代码库回到「只剩基础设施 + Emby 客户端 + 认证」的可编译状态。审查方式是核对白名单，而不是逐行看删除。

**前置条件**：

1. 当前 `main` 已打标签 `v1-final` 并推送，作为 V1 代码可回溯基线。
2. 现有 `study.db` 已按 ADR-0031 备份归档，路径已记录。

**服务端保留（白名单，其余全删）**：

```text
ShanganApplication
common/config/       SqliteConfiguration、SecurityConfiguration、ApplicationConfiguration、OpenApiConfiguration
common/api/          ApiExceptionHandler、BusinessException、RequestIdFilter、RequestLoggingInterceptor
common/auth/         CurrentUser、CurrentUserArgumentResolver
common/              IdGenerator、UuidIdGenerator
common/integration/  RuntimeIntegrationSettings、RuntimeIntegrationSettingsService、
                     JdbcRuntimeIntegrationSettingsRepository、RuntimeIntegrationSettingsRepository、
                     IntegrationSettingsProvider、EnvironmentIntegrationSettings、
                     IntegrationSettingsValidationException
identity/            JwtService、AuthService、AuthController、User、UserRepository、JdbcUserRepository
media/emby/          EmbyClient、EmbyProperties、EmbyGateway、EmbyDtos、EmbySourceFingerprint、
                     EmbyStreamProxy、EmbyHealthService
admin/               AdminLoginController、AdminEntryController、HealthAdminController、
                     AdminDisplayFormatter、OperationsHealthService
templates/admin/     login.html、health.html、fragments.html
```

**服务端删除**：`debt`、`quiz`、`planning`、`reporting`、`focus`、`ai`（含 `ai.content`）、`dashboard`、`learning`、`catalog`、`exam` 全部；`admin` 与 `templates/admin` 中白名单之外的全部（含 14 个模板）；`media/emby/EmbyAudioClient`、`EmbyPlaybackClient`（V2 无 ASR、无播放票据）。

**Flutter 保留（白名单，其余全删）**：

```text
lib/main.dart
lib/app/            app.dart、bootstrap.dart、application_bootstrap.dart
lib/core/api/       全部
lib/core/auth/      全部
lib/core/storage/   全部
lib/core/config/    全部
lib/core/theme/     shangan_theme.dart
lib/core/device/    screen_wake_lock.dart
lib/core/widgets/   shangan_markdown.dart、shangan_ui.dart（逐组件裁剪，见下）
lib/features/auth/  全部
```

`shangan_ui.dart` 保留 `ShanganEyebrow`、`ShanganStatusTag`、`ShanganSurface`、`ShanganNotice`、`ShanganNavRow`、`ShanganMetricGrid`、`ShanganProgress`、`ShanganCountUpPercent`、`ShanganIdleScrollbar`、`ShanganLoading`；删除 `ShanganWatchProgress`、`ShanganTrustScale` + `_TrustScalePainter` + `_Legend`、`ShanganIdleMotion`、`ShanganCompletionHero` + `_CompletionRingPainter`、`_StripedProgressPainter`。

**Flutter 删除**：`lib/features` 除 `auth` 外全部；`lib/app/router.dart` 精简为只含 `/login`、`/loading`、`/connection-unavailable`、`/home` 占位（完整路由表在 T21 建立）。

**测试基线**：

- 服务端保留 `test/.../common`、`identity`、`media/emby`；删除 `acceptance`、`ai`、`catalog`、`dashboard`、`debt`、`exam`、`focus`、`learning`、`planning`、`quiz`、`reporting`、`admin`、`api`。
- Flutter 保留 `test/core/api`、`test/core/auth`、`test/core/config`、`test/core/theme`、`test/features/auth`；删除 `test/features` 其余全部（含遗留 `ai_chat`、`companion`）、`test/app/router_test.dart`、`test/core/widgets` 中针对被删组件的用例。

**配置清理**：`.env.example`、`infra/compose*.yml`、`infra/deploy.env.example` 中删除 ASR / LLM / OpenRouter / 播放票据相关变量；`IntegrationSettingsForm` 与 `integration-settings.html` 在 T20 重建，本 Task 直接删除。

**不动**：`infra/`（除环境变量）、`run.sh`、`test.sh`、`Makefile`、CI workflow、`.sdkmanrc`、FVM 配置。

**窄验证**：

```bash
cd apps/server && ./mvnw -q -DskipTests compile
cd apps/server && ./mvnw -q test          # 只剩白名单内的存量测试，必须全绿
cd apps/ios && fvm flutter analyze
cd apps/ios && fvm flutter test
```

**验收检查**：

1. 服务端编译通过，保留的存量测试全绿。
2. Flutter 静态分析无错误，保留的存量测试全绿。
3. 白名单核对：`find apps/server/src/main/java -name '*.java' | wc -l` 与白名单条目一致；`ls apps/ios/lib/features` 只有 `auth`。
4. `grep -ril "quiz\|debt\|mock_exam\|alive_check\|watch_session\|companion\|content_job\|openrouter" apps/server/src apps/ios/lib` 无命中。
5. 真实 ApplicationContext 启动 Smoke Test 通过，`/actuator/health` 为 `UP`（此时数据库仍是 V1 schema，只验证 Bean 装配；V2 schema 在 T02）。

**提交主题**：`refactor!: 按白名单重建代码基线，废弃 V1 业务层`

---

### T02 重建数据库 baseline（评审门禁）

**目标**：按 ADR-0031 删除 `V001` ~ `V029`，新建 `V001__shangan_v2_baseline.sql`，定义 Spec 第 11 章全部表、索引、约束与初始数据。

**文件**：

```text
apps/server/src/main/resources/db/migration/V001__shangan_v2_baseline.sql   新建
apps/server/src/main/resources/db/migration/V0*.sql                          删除（29 个）
```

**内容要求**：

- 表：`users`、`user_roles`、`refresh_tokens`、`supervisions`、`exam_goals`、`courses`、`learning_resources`、`course_genres`、`course_tags`、`course_people`、`resource_source_mappings`、`todos`、`todo_note_tags`、`todo_attachments`、`todo_progress_events`、`lesson_watch_states`、`todo_deletions`、`user_presence`、`nag_policies`、`nags`、`nag_deliveries`、`deletion_audits`、`runtime_settings`、`SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES`。
- 全部约束按 Spec 11.3，含四个部分唯一索引：`supervisions` 的 PRIMARY 唯一、`exam_goals` 的主目标唯一、`nags` 的 AUTO 幂等键、`nag_policies` 的 scope 唯一。
- 外键方向按 ADR-0029：指向 `users` 用 `ON DELETE CASCADE`，指向 `courses` / `learning_resources` 用 `ON DELETE RESTRICT`。
- 初始数据：`nag_policies` 一行 `scope='GLOBAL'` 默认值（scan 5 / grace 150 / heartbeat 60 / first 90 / repeat 60 / dailyMax 3 / fullscreenTimeout 10 / quiet 23:30-07:00 / minPending 1 / minReason 5 / 两渠道开启）；`runtime_settings` 单行含 `features_document_resources=0`。

**前置动作**：备份现有 `study.db`（`.backup` + `PRAGMA integrity_check`）并归档到仓库外，记录路径。

**窄验证**：

```bash
# 在独立副本上验证，不得改写真实数据库
DATA_DIR=$(mktemp -d) ./run.sh server   # 启动后确认 Flyway 成功
curl --fail http://127.0.0.1:18080/actuator/health
```

**验收检查**：新库迁移成功且健康端点 `UP`；`PRAGMA foreign_key_check` 为空；四个部分唯一索引存在；全局催办策略与运行配置各一行。

**提交主题**：`feat: 重建 V2 数据库 baseline`

---

## 阶段 1：服务端核心

### T03 身份、角色与督学绑定

**目标**：用户 CRUD 基础、角色、`supervisions` 读写与权限校验组件。

**文件**：

```text
identity/domain/User.java、UserRole.java、UserStatus.java
identity/infrastructure/JdbcUserRepository.java、JdbcUserRoleRepository.java
supervision/domain/Supervision.java、SupervisionKind.java、SupervisionPermissions.java
supervision/infrastructure/JdbcSupervisionRepository.java
supervision/application/SupervisionService.java、SupervisionGuard.java
common/auth/CurrentUser.java（扩展 roles）
```

**接口**：

```java
SupervisionService.primarySupervisorOf(String learnerUserId): Optional<String>
SupervisionService.learnersOf(String supervisorUserId): List<SupervisionView>
SupervisionGuard.requirePermission(String supervisorUserId, String learnerUserId, SupervisionPermission p): void
```

**窄测试**：`SupervisionGuardTest`（无绑定拒绝、已归档绑定拒绝、权限开关关闭拒绝、协同督学可查看不可催办）、`SupervisionServiceTest`（PRIMARY 唯一性冲突返回稳定 errorCode）。

```bash
cd apps/server && ./mvnw -q -Dtest='SupervisionGuardTest,SupervisionServiceTest' test
```

**提交主题**：`feat: 新增督学绑定与权限校验`

---

### T04 考试目标

**目标**：目标 CRUD + 主目标唯一 + 剩余天数由服务端计算。

**文件**：

```text
goal/domain/ExamGoal.java
goal/infrastructure/JdbcExamGoalRepository.java
goal/application/ExamGoalService.java
goal/api/ExamGoalController.java、ExamGoalDtos.java
```

**接口**：`GET/POST/PATCH/DELETE /api/v1/exam-goals`，响应含 `daysRemaining` 与 `urgency`（`NORMAL|SOON|URGENT`，按 60 / 30 天分档）。

**窄测试**：`ExamGoalServiceTest`（主目标切换互斥、`daysRemaining` 按用户时区、过期目标返回负数天并标 `EXPIRED`）、`ExamGoalControllerTest`（归属校验）。

```bash
cd apps/server && ./mvnw -q -Dtest='ExamGoalServiceTest,ExamGoalControllerTest' test
```

**提交主题**：`feat: 考试目标倒计时看板`

---

### T05 课程库查询与元数据投影

**目标**：`courses` / `learning_resources` / 三张投影表的读写，以及 facets 聚合查询。

**文件**：

```text
catalog/domain/Course.java、LearningResource.java、ResourceType.java、CourseStatus.java
catalog/domain/ResourceMetadata.java
catalog/infrastructure/JdbcCourseRepository.java、JdbcLearningResourceRepository.java
catalog/infrastructure/JdbcCourseTaxonomyRepository.java（投影表整表重写）
catalog/application/CatalogQueryService.java
catalog/api/CatalogController.java、CatalogDtos.java
```

**接口**：

```java
CatalogQueryService.facets(userId): CatalogFacets            // genres/tags/people/years + 计数
CatalogQueryService.courses(userId, CourseFilter): List<CourseSummary>   // 含本人进度
CatalogQueryService.course(userId, courseId): CourseDetail    // 含资源列表与 LessonWatchState
JdbcCourseTaxonomyRepository.replaceAll(courseId, ResourceMetadata)      // 先删后插
```

**规则**：`ARCHIVED` 或 `sourceMissing` 的课程不出现在学习端；`features.document_resources=false` 时过滤 `DOCUMENT` 资源。

**窄测试**：`CatalogQueryServiceTest`（按流派 / 标签 / 人物 / 年份筛选、groupBy 分组、归档与失联课程被过滤、DOCUMENT 开关过滤）、`CourseTaxonomyReplaceTest`（整表重写不残留旧值）。

```bash
cd apps/server && ./mvnw -q -Dtest='CatalogQueryServiceTest,CourseTaxonomyReplaceTest' test
```

**提交主题**：`feat: 课程库查询与 Emby 元数据投影`

---

### T06 Emby 同步、映射与失联迁移

**目标**：按 Spec 12 章实现同步流程、映射优先级、变更处置与重新绑定方案生成。

**文件**：

```text
media/emby/EmbyClient.java（保留并适配新 DTO）
catalog/application/EmbyCatalogReader.java（产出 ResourceMetadata，视频与书籍共用）
catalog/application/ResourceMappingPlanner.java
catalog/application/CourseSyncService.java
catalog/application/RebindPlanner.java
catalog/infrastructure/JdbcResourceSourceMappingRepository.java
```

**接口**：

```java
ResourceMappingPlanner.plan(List<LearningResource> local, List<ResourceMetadata> remote): MappingPlan
// MappingPlan: inPlace(资源→新 externalRef)、created、markedUnavailable、ambiguous
RebindPlanner.plan(String courseId, String newParentRef): MappingPlan
CourseSyncService.sync(String courseId): SyncResult
```

**规则**：映射优先级 externalRef → 课程内唯一指纹 → 唯一标题且时长差 ≤2 秒 → 人工确认；歧义一律进 `ambiguous`，不自动合并；任一页读取失败不写部分快照；父节点 404 置 `sourceMissing=1` 并保留上次快照；`Path` 只在内存中用于指纹。

**窄测试**：`ResourceMappingPlannerTest`（四级优先级各一例、一对多与多对一进 ambiguous、时长差 3 秒不匹配）、`CourseSyncServiceTest`（分页失败整体放弃、空结果且父节点有效才全标不可用、元数据变更只重写投影不动资源 ID）、`RebindPlannerTest`（原位 / 新增 / 下架三类结果）、`EmbyCatalogReaderTest`（WireMock，字段映射与指纹不外泄、Path 不进入任何输出）。

```bash
cd apps/server && ./mvnw -q -Dtest='ResourceMappingPlannerTest,CourseSyncServiceTest,RebindPlannerTest,EmbyCatalogReaderTest' test
```

**提交主题**：`feat: Emby 同步映射与失联迁移`

---

### T07 Todo 基础读写与视图查询

**目标**：Todo 创建（批量）、修改、排序、日 / 周 / 月视图查询、顺延、未完成汇总。

**文件**：

```text
todo/domain/Todo.java、TodoType.java、TodoStatus.java
todo/infrastructure/JdbcTodoRepository.java
todo/application/TodoService.java、TodoViewService.java
todo/api/TodoController.java、TodoDtos.java
```

**接口**：

```java
TodoService.create(userId, List<CreateTodoCommand>): List<TodoView>
TodoService.patch(userId, todoId, PatchTodoCommand): TodoView
TodoService.defer(userId, todoId, LocalDate target): TodoView
TodoService.reorder(userId, localDate, List<String> orderedIds): void
TodoViewService.day(userId, LocalDate): DayView
TodoViewService.week(userId, LocalDate weekStart): WeekSummary     // 仅聚合
TodoViewService.month(userId, YearMonth): MonthSummary             // 仅聚合
TodoViewService.pendingSummary(userId): PendingSummary             // 跨日期，按逾期天数分组
```

**规则**：`localDate` 由服务端按用户时区生成，客户端只能传目标日期；课程 Todo 必须带 `resourceId` 与 `targetProgressPermille`（默认 1000）；专注 Todo 必须带 `plannedSeconds`（60–43200）；顺延保留进度与附件。

**窄测试**：`TodoServiceTest`（三类型必填校验、目标进度范围 1–1000、顺延保留进度、跨时区 localDate 计算）、`TodoViewServiceTest`（周月只返聚合、分组顺序为进行中→待开始→已完成、pendingSummary 按逾期天数分档）。

```bash
cd apps/server && ./mvnw -q -Dtest='TodoServiceTest,TodoViewServiceTest' test
```

**提交主题**：`feat: Todo 读写与日周月视图`

---

### T08 进度上报与完成判定

**目标**：幂等进度上报、`positionMs` 单调、目标进度达标自动完成、`lesson_watch_states` 累计、补记完成。

**文件**：

```text
todo/domain/TodoProgressPolicy.java
todo/infrastructure/JdbcTodoProgressEventRepository.java、JdbcLessonWatchStateRepository.java
todo/application/TodoProgressService.java、TodoCompletionService.java
todo/api/TodoProgressController.java
```

**接口**：

```java
TodoProgressService.report(userId, todoId, ProgressReport): ProgressResult
TodoCompletionService.complete(userId, todoId, CompleteCommand): TodoView   // 含 backfill
```

**规则**：`(todoId, clientSeq)` 唯一，重复提交返回当前状态不重复计数；`positionMs` 取 max；`deltaWatchedMs` 只在 `FOREGROUND` 累加；达标判定 `position/duration*1000 >= target`；达标自动 `DONE` 但允许继续累计；`requireEvidence` 且无附件时返回 `TODO_EVIDENCE_REQUIRED`；`backfill=true` 只允许历史日期且 `note` 必填，置 `backfilled=1`；每次成功上报刷新 `lastEffectiveActionAt`。

**窄测试**：`TodoProgressPolicyTest`（单调、达标边界 499/500、倍速不影响时长口径）、`TodoProgressServiceTest`（同一 clientSeq 重放不重复累计、乱序重放结果一致、后台状态不累计时长、`lesson_watch_states` 跨 Todo 累计不清零）、`TodoCompletionServiceTest`（凭证必填、补记必填备注、补记不计入当日时长、当天日期禁止 backfill）。

```bash
cd apps/server && ./mvnw -q -Dtest='TodoProgressPolicyTest,TodoProgressServiceTest,TodoCompletionServiceTest' test
```

**提交主题**：`feat: 进度上报幂等与目标进度完成判定`

---

### T09 专注状态机

**目标**：专注 Todo 的 `focusState` 状态机与两种终态统计。

**文件**：

```text
todo/domain/FocusState.java、FocusPolicy.java
todo/application/FocusService.java
todo/api/FocusController.java
```

**接口**：

```java
FocusService.start(userId, todoId): TodoView
FocusService.pause / resume(userId, todoId): TodoView
FocusService.finish(userId, todoId, CompleteCommand): TodoView     // → DONE
FocusService.abandon(userId, todoId, AbandonCommand): TodoView      // → 未完成，保留 focusedMs
```

**规则**：非法转移返回稳定业务错误码；`FINISHED` 置 Todo `DONE`；`ABANDONED` 保持未完成且保留已累计 `focusedMs`；要求拍照时 `finish` 无附件则拒绝；同一用户同一时刻只允许一个 `RUNNING` 专注。

**窄测试**：`FocusPolicyTest`（合法与非法转移矩阵）、`FocusServiceTest`（并发 RUNNING 拒绝、放弃保留时长、finish 凭证校验）。

```bash
cd apps/server && ./mvnw -q -Dtest='FocusPolicyTest,FocusServiceTest' test
```

**提交主题**：`feat: 专注计时状态机`

---

### T10 附件与备注标签

**目标**：Todo 附件上传 / 删除、备注与一键标签写入。

**文件**：

```text
todo/domain/TodoAttachment.java、NoteTag.java
todo/infrastructure/JdbcTodoAttachmentRepository.java、JdbcTodoNoteTagRepository.java
todo/application/TodoAttachmentService.java、AttachmentStorage.java、LocalAttachmentStorage.java
todo/api/TodoAttachmentController.java
```

**规则**：单文件 ≤10MB、单 Todo ≤9 个、允许 `image/*` 与 `application/pdf`；文件名由服务端生成，存 `DATA_DIR/attachments/{userId}/`；记录 `sha256`；下载与删除做归属校验；备注标签为固定枚举（`MASTERED`、`NEED_REVIEW`、`HAS_QUESTION`、`NOTED`、`BEHIND`）。

**窄测试**：`TodoAttachmentServiceTest`（超大拒绝、超数量拒绝、非法类型拒绝、越权拒绝、路径穿越拒绝）、`NoteTagTest`（未知标签拒绝）。

```bash
cd apps/server && ./mvnw -q -Dtest='TodoAttachmentServiceTest,NoteTagTest' test
```

**提交主题**：`feat: Todo 附件与备注标签`

---

### T11 删除台账

**目标**：删除 Todo 必填原因，写台账并触发督学通知规则。

**文件**：

```text
todo/domain/TodoDeletion.java、DeletionReasonTag.java
todo/infrastructure/JdbcTodoDeletionRepository.java
todo/application/TodoDeletionService.java、SupervisorNotificationRules.java
todo/api/TodoDeletionController.java（DELETE 与 batch-delete）
```

**规则**：`reasonTag` 必为枚举、`reasonText` 长度 ≥ `minReasonLength`；单事务内写台账（含进度快照 JSON 与督学人快照）→ 删附件行与磁盘文件 → 删进度事件 → 删 `todos`；批量删除共用原因；通知规则：单日删除 ≥3、`reasonTag=GAVE_UP`、删除进度 ≥50% 的课程 Todo。

**窄测试**：`TodoDeletionServiceTest`（缺原因拒绝、原因过短拒绝、台账含进度与督学人快照、附件文件被删除、批量共用原因）、`SupervisorNotificationRulesTest`（三条规则各一例 + 不触发一例）。

```bash
cd apps/server && ./mvnw -q -Dtest='TodoDeletionServiceTest,SupervisorNotificationRulesTest' test
```

**提交主题**：`feat: Todo 删除台账与督学通知规则`

---

### T12 心跳与在线状态

**目标**：心跳接口、`user_presence` 维护、在线与空闲判定、有效操作记录点。

**文件**：

```text
presence/domain/PresenceStatus.java、PresencePolicy.java
presence/infrastructure/JdbcUserPresenceRepository.java
presence/application/PresenceService.java、EffectiveActionRecorder.java
presence/api/HeartbeatController.java
```

**接口**：

```java
PresenceService.heartbeat(userId, HeartbeatRequest): HeartbeatResponse
PresenceService.statusOf(userId): PresenceStatus        // ONLINE / IDLE / OFFLINE + idleMinutes
EffectiveActionRecorder.record(userId, Instant at): void
```

**规则**：不落心跳明细；心跳只更新 `lastHeartbeatAt` 与 `appState`，**不更新** `lastEffectiveActionAt`；在线判定用 `presenceGraceSeconds`；响应下发 `heartbeatIntervalSeconds` 与 `pendingNagId`。

**窄测试**：`PresencePolicyTest`（在线 / 空闲 / 离线边界、心跳不刷新有效操作）、`PresenceServiceTest`（响应下发间隔与待回应催办、幂等 upsert）。

```bash
cd apps/server && ./mvnw -q -Dtest='PresencePolicyTest,PresenceServiceTest' test
```

**提交主题**：`feat: 心跳与在线状态判定`

---

### T13 催办策略、扫描与渠道

**目标**：策略读取与合并、扫描器、幂等生成、渠道抽象与降级、回应。

**文件**：

```text
nag/domain/Nag.java、NagStatus.java、NagTrigger.java、NagChannel.java、NagPolicy.java
nag/domain/NagScanPolicy.java
nag/infrastructure/JdbcNagRepository.java、JdbcNagPolicyRepository.java、JdbcNagDeliveryRepository.java
nag/application/NagPolicyResolver.java、NagScanner.java、NagScanScheduler.java
nag/application/NagDeliveryService.java
nag/application/channel/FullscreenNagChannel.java、ServerChanNagChannel.java
nag/api/NagController.java
```

**接口**：

```java
NagPolicyResolver.resolve(userId): EffectiveNagPolicy    // GLOBAL + USER 覆盖，调度类字段只取全局
NagScanPolicy.evaluate(EffectiveNagPolicy, PresenceStatus, pendingCount, localTime): Optional<Integer> // 返回 thresholdLevel
NagScanner.scanOnce(): ScanReport
NagDeliveryService.deliver(Nag): void                     // 渠道选择与降级
NagController.respond(userId, nagId, RespondCommand): NagView
```

**规则**：幂等键 `(userId, localDate, level) WHERE trigger='AUTO'`；免打扰只累计不投递；`dailyMax` 含手动与督学催办；在线走全屏、离线走 Server 酱；全屏超时追加 Server 酱投递（同一 `Nag`）；回应必填原因且长度校验；回应刷新 `lastEffectiveActionAt`；Server 酱调用带超时，失败写 `FAILED`。

**窄测试**：`NagPolicyResolverTest`（用户覆盖生效、调度字段不被覆盖）、`NagScanPolicyTest`（低于阈值不触发、分档计算、repeat=0 恒为 level 1、免打扰、pendingCount 不足、dailyMax 用尽）、`NagScannerTest`（同一天同一档幂等、多用户独立时区）、`NagDeliveryServiceTest`（在线选全屏、离线选 Server 酱、全屏超时追加投递、外部失败记录 FAILED）、`ServerChanNagChannelTest`（WireMock 超时与脱敏）。

```bash
cd apps/server && ./mvnw -q -Dtest='NagPolicyResolverTest,NagScanPolicyTest,NagScannerTest,NagDeliveryServiceTest,ServerChanNagChannelTest' test
```

**提交主题**：`feat: 催办策略扫描与渠道降级`

---

### T14 统计聚合

**目标**：日 / 周 / 月统计与排行、热力图分档、备注标签聚合。

**文件**：

```text
stats/application/StatsService.java、StatsAggregationPolicy.java
stats/infrastructure/JdbcStatsRepository.java
stats/api/StatsController.java、StatsDtos.java
```

**规则**：按用户时区归集 `occurredAt`；观看与专注时长分列；补记完成单列且不计入当日时长；排行按 `watchedMs` 降序并通过投影表连接人物 / 流派；热力图分档 无 Todo / 有 Todo 零完成 / `<1h` / `1–3h` / `>3h`；专注完成率 = `FINISHED / (FINISHED + ABANDONED)`。

**窄测试**：`StatsAggregationPolicyTest`（跨时区归集、跨日事件归属、热力图五档边界、专注完成率、补记不计时长）、`StatsServiceTest`（日周月三种范围的字段完整性）。

```bash
cd apps/server && ./mvnw -q -Dtest='StatsAggregationPolicyTest,StatsServiceTest' test
```

**提交主题**：`feat: 日周月统计聚合`

---

### T15 督学端 API

**目标**：学员总览、学员详情、一键督学、提醒时间线、学员报告。

**文件**：

```text
supervision/application/SupervisorViewService.java、SupervisorNagService.java
supervision/api/SupervisorController.java、SupervisorDtos.java
```

**接口**：

```java
SupervisorViewService.learners(supervisorUserId): List<LearnerOverview>
SupervisorViewService.learnerDetail(supervisorUserId, learnerId, StatsRange): LearnerDetail
SupervisorViewService.feed(supervisorUserId, FeedFilter): List<FeedItem>   // 我发的/自动/删除通知
SupervisorViewService.report(supervisorUserId, StatsRange): SupervisorReport
SupervisorNagService.nag(supervisorUserId, learnerId, SupervisorNagCommand): NagView
```

**规则**：每个方法先过 `SupervisionGuard`；返回只读镜像，不含任何写入路径；一键督学 `trigger=SUPERVISOR` 且记录发起人，计入 `dailyMax`，不参与自动幂等键；异常徽标口径：零完成、单月删除 ≥5、催办未回应。

**窄测试**：`SupervisorViewServiceTest`（未绑定越权拒绝、协同督学无催办权限时 nag 拒绝、异常徽标判定）、`SupervisorNagServiceTest`（记录 trigger 与发起人、超过 dailyMax 拒绝并给稳定错误码）。

```bash
cd apps/server && ./mvnw -q -Dtest='SupervisorViewServiceTest,SupervisorNagServiceTest' test
```

**提交主题**：`feat: 督学端只读视图与一键督学`

---

### T16 归档与彻底删除

**目标**：归档状态流转、彻底删除预检与级联执行、审计、孤儿自检。

**文件**：

```text
archive/domain/ArchivableEntityType.java、CascadePlan.java
archive/application/ArchiveService.java、CascadeDeletionService.java
archive/application/DeletionPreflightService.java、OrphanScanService.java
archive/infrastructure/JdbcCascadeDeletionRepository.java、JdbcDeletionAuditRepository.java
```

**接口**：

```java
ArchiveService.archive(type, id, actor): void
ArchiveService.restore(type, id, actor): void
DeletionPreflightService.preflight(type, id): CascadePlan   // 表、行数、文件占用、影响用户与统计
CascadeDeletionService.purge(type, id, actor, String confirmedLabel): DeletionAudit
OrphanScanService.scan(): OrphanReport
```

**规则**：删除顺序严格按 Spec 11.4；单事务；磁盘文件删除失败视为整体失败并回滚；`confirmedLabel` 必须与实体名称一致；归档对象不可被新建引用；归档保留期到期只提醒；写 `deletion_audits` 但不含内容明细；孤儿自检四类检查项。

**窄测试**：`CascadePlanTest`（课程与用户两条顺序的断言，顺序错误即失败）、`DeletionPreflightServiceTest`（行数统计与影响面）、`CascadeDeletionServiceTest`（名称不匹配拒绝、文件删除失败整体回滚、审计写入）、`ArchiveServiceTest`（归档后不可新建引用、恢复可用）、`OrphanScanServiceTest`（四类孤儿各一例）。

```bash
cd apps/server && ./mvnw -q -Dtest='CascadePlanTest,DeletionPreflightServiceTest,CascadeDeletionServiceTest,ArchiveServiceTest,OrphanScanServiceTest' test
```

**提交主题**：`feat: 归档与级联彻底删除`

---

## 阶段 2：管理后台

### T17 后台概览与在线监控

**目标**：概览页与在线进度页，含手动催办入口。

**文件**：

```text
admin/OverviewAdminController.java、PresenceAdminController.java
admin/OperationsHealthService.java（改造：去掉 ASR/LLM，加入扫描器与备份状态）
resources/templates/admin/overview.html、presence.html
resources/templates/admin/fragments/nav.html（新导航：概览/在线与进度/催办策略/催办与删除/课程库/Emby 同步/督学关系/用户/归档区/运行配置）
```

**内容**：在线用户数、今日全局完成率与时长、待处理催办、依赖健康（Emby / Server 酱 / SQLite / 最近备份 / 催办扫描）、近 14 天时长柱状；在线页含心跳与有效操作明细、今日 Todo 展开、手动催办表单。

**窄测试**：`OverviewAdminControllerTest`、`PresenceAdminControllerTest`（MockMvc 切片：未登录跳转、模型属性齐全、手动催办 CSRF 校验）。

```bash
cd apps/server && ./mvnw -q -Dtest='OverviewAdminControllerTest,PresenceAdminControllerTest' test
```

**提交主题**：`feat: 后台概览与在线监控`

---

### T18 后台催办策略与记录

**目标**：策略表单（全局 + 用户覆盖）、催办记录与删除台账页。

**文件**：

```text
admin/NagPolicyAdminController.java、NagPolicyForm.java
admin/NagRecordAdminController.java、TodoDeletionAdminController.java
resources/templates/admin/nag-policy.html、nags.html、todo-deletions.html
```

**内容**：全局默认表单（扫描周期、在线宽限、心跳间隔、首次阈值、重复间隔、每日上限、全屏超时、免打扰、最少未完成、原因最少字数、两个渠道开关、文案模板）；按用户覆盖列表与增删改；催办记录筛选（用户 / 日期 / 渠道 / 回应状态）；删除台账（原因分布、按学员、流水、CSV 导出、三条督学通知规则开关）。

**窄测试**：`NagPolicyFormTest`（范围校验、模板变量白名单）、`NagPolicyAdminControllerTest`、`TodoDeletionAdminControllerTest`（CSV 导出脱敏、筛选条件透传）。

```bash
cd apps/server && ./mvnw -q -Dtest='NagPolicyFormTest,NagPolicyAdminControllerTest,TodoDeletionAdminControllerTest' test
```

**提交主题**：`feat: 后台催办策略与记录`

---

### T19 后台课程库与 Emby 同步

**目标**：课程列表（只读元数据列）、资源清单、同步页、待确认映射、失联处置、重新绑定向导。

**文件**：

```text
admin/CourseAdminController.java（重写）
admin/EmbySyncAdminController.java、RebindForm.java
resources/templates/admin/courses.html、course-resources.html、emby-sync.html、rebind.html
```

**内容**：课程列表含流派 / 人物 / 标签 / 年份只读列与「资源 / 同步 / 归档」动作；课程详情只可改排序与启用；同步页含元数据变更预览、映射待确认逐项操作、失联处置、重新绑定向导三步与单事务提交。

**窄测试**：`CourseAdminControllerTest`（元数据字段只读、列表页无物理删除入口）、`EmbySyncAdminControllerTest`（待确认逐项确认、重新绑定提交调用 RebindPlanner 与单事务服务）。

```bash
cd apps/server && ./mvnw -q -Dtest='CourseAdminControllerTest,EmbySyncAdminControllerTest' test
```

**提交主题**：`feat: 后台课程库与 Emby 同步页`

---

### T20 后台督学关系、用户、归档区与运行配置

**目标**：督学绑定维护、用户管理（归档而非删除）、归档区与彻底删除、运行配置精简。

**文件**：

```text
admin/SupervisionAdminController.java、SupervisionForm.java
admin/UserAdminController.java（改造为归档）
admin/ArchiveAdminController.java
admin/IntegrationSettingsAdminController.java、IntegrationSettingsForm.java（精简为 Emby + Server 酱 + features）
resources/templates/admin/supervisions.html、users.html、archive.html、settings.html
```

**内容**：绑定列表与四项权限开关、事件督学人快照查看；用户新增 / 改密 / 时区 / 归档，显示累计学习与附件占用；归档区四类对象、预检清单、名称二次确认、孤儿自检、保留期设置；运行配置只留 Emby 与 Server 酱与 `features.document_resources`，测试连接与发送测试。

**窄测试**：`SupervisionAdminControllerTest`（PRIMARY 唯一冲突提示）、`UserAdminControllerTest`（列表页只有归档、无物理删除）、`ArchiveAdminControllerTest`（预检展示、名称不匹配拒绝、CSRF）、`IntegrationSettingsFormTest`（ASR/LLM 字段已移除、密钥不回显）。

```bash
cd apps/server && ./mvnw -q -Dtest='SupervisionAdminControllerTest,UserAdminControllerTest,ArchiveAdminControllerTest,IntegrationSettingsFormTest' test
```

**提交主题**：`feat: 后台督学关系、用户归档与归档区`

---

## 阶段 3：移动端学习端

### T21 主题、路由与 AppShell 重构

**目标**：清理路由、重建 4 Tab 骨架、补齐 V2 需要的共享组件。

**文件**：

```text
lib/app/router.dart（重写路由表）
lib/features/shell/presentation/app_shell.dart
lib/core/theme/shangan_theme.dart（补充督学端赭色强调、目标看板与播放器所需 token）
lib/core/widgets/shangan_ui.dart（清理废弃组件，新增 GoalBoard、TodoRow、SegmentedView、StatStrip）
```

**路由**：`/login`、`/home`、`/courses/:id`、`/player/:todoId`、`/focus/:todoId`、`/todos/pending`、`/goals`、`/settings`、`/supervisor/*`。删除 `/plan`、`/debts`、`/quiz`、`/mock-exam`、`/mock-exam-presets`、`/reports/*`。

**窄测试**：`router_test.dart`（废弃路由不可达、鉴权重定向）、`goal_board_test.dart`（多目标全部渲染、临期着色分档）。

```bash
cd apps/ios && fvm flutter test test/app/router_test.dart test/core/widgets/goal_board_test.dart
```

**提交主题**：`feat: Flutter 路由与 AppShell 重构`

---

### T22 首页今日视图

**目标**：目标看板 + 今日指标 + 分组 Todo 列表 + 编辑态（排序、批量顺延、批量删除入口）。

**文件**：

```text
lib/features/todo/data/todo_repository.dart、todo_dtos.dart
lib/features/todo/presentation/home_page.dart、todo_group_list.dart、home_edit_mode.dart
lib/features/goal/data/goal_repository.dart
```

**窄测试**：`home_controller_test.dart`（分组顺序、指标计算来自服务端字段而非本地推算）、`home_edit_mode_test.dart`（批量选择、删除进入原因弹窗）。

```bash
cd apps/ios && fvm flutter test test/features/todo/home_controller_test.dart test/features/todo/home_edit_mode_test.dart
```

**提交主题**：`feat: 首页今日 Todo 与目标看板`

---

### T23 日周月视图、历史日与未完成汇总

**目标**：视图切换器、周视图折叠卡、月视图日历、历史日三种补救动作、未完成汇总页、删除原因弹窗。

**文件**：

```text
lib/features/todo/presentation/view_switcher.dart、week_view.dart、month_view.dart
lib/features/todo/presentation/history_day_page.dart、pending_summary_page.dart
lib/features/todo/presentation/delete_reason_dialog.dart
```

**窄测试**：`delete_reason_dialog_test.dart`（未选标签或不足 5 字时确认禁用；提交携带 tag 与 text）、`history_day_test.dart`（历史日展示顺延 / 补记 / 删除三动作，当天不展示补记）、`view_switcher_test.dart`（三档切换与本地记忆）。

```bash
cd apps/ios && fvm flutter test test/features/todo/delete_reason_dialog_test.dart test/features/todo/history_day_test.dart test/features/todo/view_switcher_test.dart
```

**提交主题**：`feat: 日周月视图与历史待办处理`

---

### T24 添加流程

**目标**：类型选择、课程两级选择与目标进度、专注新建、待办新建。

**文件**：

```text
lib/features/todo/presentation/add_todo_sheet.dart、pick_course_sheet.dart、pick_resource_sheet.dart
lib/features/todo/presentation/new_focus_sheet.dart、new_task_sheet.dart
```

**窄测试**：`add_todo_flow_test.dart`（三类型入口、课程多选合计目标时长、目标进度默认 100% 可改、专注时长范围校验、待办必填标题）。

```bash
cd apps/ios && fvm flutter test test/features/todo/add_todo_flow_test.dart
```

**提交主题**：`feat: 三类 Todo 添加流程`

---

### T25 播放页与进度上报

**目标**：播放页（快退 / 快进 10 秒、倍速、全屏、目标刻度线）、上报队列与离线重放、屏幕常亮。

**文件**：

```text
lib/features/player/presentation/player_page.dart、player_controls.dart、fullscreen_player_page.dart
lib/features/player/domain/progress_reporter.dart、progress_queue.dart
lib/features/player/data/player_repository.dart
lib/core/device/screen_wake_lock.dart（复用）
```

**规则**：15 秒上报 + 暂停 / 退出 / 完成 / 切后台各补一次；`clientSeq` 本地单调；后台不累计 `watchedMs`；倍速不改时长口径；离线入队，恢复按序重放；完成判定只采用服务端响应。

**窄测试**：`progress_reporter_test.dart`（fake adapter：15 秒节奏、后台不累计、倍速口径、切后台补报）、`progress_queue_test.dart`（离线入队、按序重放、重放不重复计数）、`player_controls_test.dart`（快进快退步长、倍速档位、全屏切换）。

```bash
cd apps/ios && fvm flutter test test/features/player/
```

**提交主题**：`feat: 播放页与进度上报队列`

---

### T26 专注运行与完成回填

**目标**：专注运行页（暂停 / 放弃 / 提前拍照）、通用完成回填面板（附件 + 备注 + 一键标签）、待办勾选确认。

**文件**：

```text
lib/features/focus/presentation/focus_run_page.dart
lib/features/todo/presentation/complete_sheet.dart、attachment_picker.dart
lib/features/todo/presentation/task_complete_dialog.dart
```

**窄测试**：`focus_run_test.dart`（放弃需二次确认、完成需拍照时拦截）、`complete_sheet_test.dart`（一键标签追加到备注并上报 noteTags、附件数量上限）、`task_complete_dialog_test.dart`（无凭证时确认按钮禁用且有文字说明）。

```bash
cd apps/ios && fvm flutter test test/features/focus/focus_run_test.dart test/features/todo/complete_sheet_test.dart test/features/todo/task_complete_dialog_test.dart
```

**提交主题**：`feat: 专注运行与完成回填`

---

### T27 学习 Tab

**目标**：按流派 / 按人物两种视图、facets 筛选、课程详情与资源列表。

**文件**：

```text
lib/features/catalog/data/catalog_repository.dart
lib/features/catalog/presentation/library_page.dart、course_detail_page.dart、facet_filter_bar.dart
```

**窄测试**：`library_controller_test.dart`（facets 渲染与叠加筛选、两种分组切换）、`course_detail_test.dart`（资源进度展示、已下架资源不可播放、DOCUMENT 在开关关闭时不出现）。

```bash
cd apps/ios && fvm flutter test test/features/catalog/
```

**提交主题**：`feat: 学习 Tab 课程库浏览`

---

### T28 数据 Tab

**目标**：日 / 周 / 月三视图，含时段分布、排行、热力图、分类占比、备注标签聚合。

**文件**：

```text
lib/features/stats/data/stats_repository.dart
lib/features/stats/presentation/stats_page.dart、charts.dart、rank_list.dart、heatmap.dart
```

**窄测试**：`stats_controller_test.dart`（三档范围切换、字段映射）、`heatmap_test.dart`（五档着色与「有 Todo 零完成」警示态）。

```bash
cd apps/ios && fvm flutter test test/features/stats/
```

**提交主题**：`feat: 数据 Tab 日周月统计`

---

### T29 我的 Tab

**目标**：账号资料、改密、时区、服务端地址、目标管理与编辑、督学人展示、催办策略只读展示。

**文件**：

```text
lib/features/profile/presentation/profile_page.dart、password_page.dart
lib/features/goal/presentation/goal_list_page.dart、goal_edit_page.dart
```

**窄测试**：`goal_edit_test.dart`（只有名称 / 日期 / 备注 / 主目标四个字段，无课程绑定入口）、`profile_page_test.dart`（催办策略为只读、无阈值可改控件）。

```bash
cd apps/ios && fvm flutter test test/features/profile/ test/features/goal/
```

**提交主题**：`feat: 我的 Tab 与考试目标编辑`

---

### T30 心跳与全屏催办

**目标**：后台心跳服务、离线与待同步提示、全屏催办弹框与回应。

**文件**：

```text
lib/core/presence/heartbeat_service.dart
lib/features/nag/data/nag_repository.dart
lib/features/nag/presentation/fullscreen_nag_page.dart、offline_banner.dart
```

**规则**：心跳间隔由服务端下发；心跳失败不影响本地播放与计时；`pendingNagId` 非空时强制拉起全屏页，不可外部点击关闭、不可返回、不可切 Tab，原因不足 5 字时提交按钮禁用。

**窄测试**：`heartbeat_service_test.dart`（间隔遵循服务端、失败重试与离线标记）、`fullscreen_nag_test.dart`（不可 dismiss、原因长度校验、提交后关闭并刷新首页）。

```bash
cd apps/ios && fvm flutter test test/core/presence/heartbeat_service_test.dart test/features/nag/fullscreen_nag_test.dart
```

**提交主题**：`feat: 心跳上报与全屏催办承接`

---

## 阶段 4：督学端与收尾

### T31 督学端

**目标**：角色切换、学员总览、学员详情、一键督学、提醒记录、学员报告。

**文件**：

```text
lib/features/supervisor/data/supervisor_repository.dart
lib/features/supervisor/presentation/role_switch_sheet.dart、supervisor_shell.dart
lib/features/supervisor/presentation/learners_page.dart、learner_detail_page.dart
lib/features/supervisor/presentation/nag_sheet.dart、feed_page.dart、report_page.dart
```

**规则**：切换不重新登录；督学端全部只读，Todo 行禁用交互；一键督学可选模板与渠道；无 `canNag` 权限时隐藏入口。

**窄测试**：`role_switch_test.dart`（无督学绑定时不显示入口）、`learner_detail_test.dart`（Todo 行不可勾选、显示删除原因）、`nag_sheet_test.dart`（模板选择、渠道选择、提交调用仓库）。

```bash
cd apps/ios && fvm flutter test test/features/supervisor/
```

**提交主题**：`feat: 督学端角色切换与一键督学`

---

### T32 OpenAPI 合同重写

**目标**：`docs/api/openapi.yaml` 重写为 V2 契约，删除全部废弃路径。

**文件**：`docs/api/openapi.yaml`

**内容**：Spec 第 10 章全部路径与 DTO；错误响应统一 Problem Details 并列出稳定 `errorCode`（`TODO_EVIDENCE_REQUIRED`、`TODO_DELETE_REASON_REQUIRED`、`FOCUS_ILLEGAL_TRANSITION`、`NAG_DAILY_LIMIT_REACHED`、`SUPERVISION_FORBIDDEN`、`RESOURCE_UNAVAILABLE`、`ARCHIVE_LABEL_MISMATCH` 等）。

**窄验证**：合同校验任务通过（CI 中的 drift 检查）。

**提交主题**：`docs: 重写 V2 OpenAPI 合同`

---

### T33 文档与仓库材料清理（评审门禁）

**目标**：删除 V1 需求材料与过期原型，更新根文档。

**删除**：

```text
docs/specs/2026-08-27-shangan-v1-design.md
docs/plans/2026-08-27-shangan-v1-implementation-plan.md
docs/traceability/2026-08-27-shangan-v1-traceability.md
docs/roadmap/2026-09-04-shangan-version-roadmap.md
docs/prototypes/shangan-ai-tutor-v2.html
docs/prototypes/shangan-admin-prototype.html
docs/superpowers/                                   （全部历史设计草稿）
docs/runbooks/v1-physical-device-acceptance.md      （由 V2 版本取代）
docs/adr/0004、0007、0008、0011、0012、0013、0017、0018、0019、0021、0022、0023、0024
```

**保留并标注为历史**：`docs/adr/0001`、`0002`、`0003`、`0005`、`0006`、`0009`、`0010`、`0014`、`0015`、`0016`、`0020`（在文件头加一行「适用范围：V1 / 已被 ADR-00xx 取代」）。

**更新**：`AGENTS.md`（必读文档改为 V2 三件套、状态机章节改写、删除 AI 与欠债规则）、`README.md`（核心能力、架构图、边界、文档索引）、`docs/prototypes/README.md`、`docs/runbooks/v2-physical-device-acceptance.md`（新建）。

**验收检查**：`grep -ril "欠债\|模拟考试\|可信播放\|作战单\|晚间审判\|毛线团团" docs *.md` 只在被明确标注为历史的 ADR 中命中。

**提交主题**：`docs: 清理 V1 需求材料并切换到 V2 文档`

---

### T34 备份恢复演练

**目标**：在 V2 schema 上验证备份与恢复流程。

**动作**：执行 `.backup` → `PRAGMA integrity_check` → 用备份副本启动 → 登录 → 校验 Todo、进度、附件、统计、催办记录完整。

**文件**：`docs/runbooks/backup-restore.md`（更新为 V2 表清单与校验项）

**提交主题**：`docs: 更新 V2 备份恢复手册`

---

### T35 真机验收（评审门禁）

**目标**：物理 iPhone、iPad、Android 上完成 Spec 第 17 章全部 26 个验收场景。

**重点**：A3 / A4（拖动、快进、倍速、全屏）、A5（断网重放）、A6 / A7（专注两种终态）、A9（删除原因）、A13（全屏催办不可绕过）、A16（督学一键催办）、A26（三端播放）。

**文件**：`docs/runbooks/v2-physical-device-acceptance.md`

**验收检查**：26 个场景全部记录实际结果；失败项建立缺陷条目后重跑。

**提交主题**：`docs: 记录 V2 真机验收结果`

---

## 风险与前置条件

| 风险 | 说明 | 应对 |
|---|---|---|
| 数据不可逆丢失 | T02 重建 baseline 会放弃 V1 全部数据 | T02 前必须完成备份归档并人工确认（评审门禁） |
| 单次 diff 极大 | T01 清空业务层，无法逐行审查 | 审查对象改为约 30 项保留白名单；编译、存量测试、静态分析与启动 Smoke 为门槛；执行前打 `v1-final` 标签兜底 |
| 保留项与 V2 不匹配 | `users` 字段、`EmbyDtos` 字段、`OperationsHealthService` 检查项、`fragments.html` 导航在 T01 后不完整 | 由 T03 / T06 / T17 / T20 各自改写；T01 只要求可编译，不要求功能完整 |
| 越权风险 | 督学端引入跨用户读取 | T03 的 `SupervisionGuard` 为唯一入口，T15 每个方法都必须过；测试覆盖未绑定与权限关闭两类拒绝 |
| 级联删除遗漏 | 新增引用表时忘记更新顺序 | `CascadePlanTest` 断言完整顺序；`OrphanScanService` 每日自检 |
| 时区口径错误 | 统计与催办都依赖用户本地日 | 统一注入 `Clock`；`StatsAggregationPolicyTest` 与 `NagScannerTest` 覆盖跨时区 |
| 预留能力腐化 | `DOCUMENT` 枚举值与 `page_count` / `position_page` 两列在 V2 无使用者 | 开关默认关闭；不允许在 V2 引入任何 PDF 依赖、书籍库同步、下载代理或页数回报接口 |

---

## 阶段 5：测试补齐

### T36 补齐 V2 测试基线（评审门禁）

**目标**：按 T03 ~ T31 各自「窄测试」小节列出的用例清单，统一补齐服务端与 Flutter 测试，恢复 `AGENTS.md` 要求的验证水位。

**范围**：

- 服务端：`SupervisionGuardTest`、`ExamGoalServiceTest`、`CatalogQueryServiceTest`、`CourseTaxonomyReplaceTest`、`ResourceMappingPlannerTest`、`CourseSyncServiceTest`、`RebindPlannerTest`、`EmbyCatalogReaderTest`、`TodoServiceTest`、`TodoViewServiceTest`、`TodoProgressPolicyTest`、`TodoProgressServiceTest`、`TodoCompletionServiceTest`、`FocusPolicyTest`、`FocusServiceTest`、`TodoAttachmentServiceTest`、`TodoDeletionServiceTest`、`SupervisorNotificationRulesTest`、`PresencePolicyTest`、`PresenceServiceTest`、`NagPolicyResolverTest`、`NagScanPolicyTest`、`NagScannerTest`、`NagDeliveryServiceTest`、`ServerChanNagChannelTest`、`StatsAggregationPolicyTest`、`StatsServiceTest`、`SupervisorViewServiceTest`、`SupervisorNagServiceTest`、`CascadePlanTest`、`DeletionPreflightServiceTest`、`CascadeDeletionServiceTest`、`ArchiveServiceTest`、`OrphanScanServiceTest`，以及后台 Controller 切片测试。
- Flutter：`router_test`、`goal_board_test`、`home_controller_test`、`home_edit_mode_test`、`delete_reason_dialog_test`、`history_day_test`、`view_switcher_test`、`add_todo_flow_test`、`progress_reporter_test`、`progress_queue_test`、`player_controls_test`、`focus_run_test`、`complete_sheet_test`、`task_complete_dialog_test`、`library_controller_test`、`course_detail_test`、`stats_controller_test`、`heatmap_test`、`goal_edit_test`、`profile_page_test`、`heartbeat_service_test`、`fullscreen_nag_test`、`role_switch_test`、`learner_detail_test`、`nag_sheet_test`。

**约束**：不得为了让测试通过而修改业务语义；发现实现与 Spec 不符时优先修实现。禁止使用 `isNotNull` 替代精确断言、禁止 mock 被测对象、禁止任意 sleep。

**窄验证**：

```bash
cd apps/server && ./mvnw verify
cd apps/ios && fvm flutter test
```

**提交主题**：`test: 补齐 V2 服务端与移动端测试基线`

## 2026-09-08 已批准变更：催办通知传输模式

- 默认 `SSE`，后台「催办策略」提供全局切换为 `HEARTBEAT`（原心跳轮询），不允许按用户覆盖。
- 新增 `GET /api/v1/nags/events`，Bearer 认证，仅订阅当前用户；`ready` 触发补查，`nag` 的 data 为催办 ID，保活注释不更新在线或有效操作。
- 催办事务成功提交后通知，数据库仍是唯一事实来源；连接断开、事件重复或丢失都通过待回应查询兜底，不新增消息历史表。
- 连接最长 60 秒后重新认证，每 15 秒保活；前台断线按 1～30 秒退避重连，回前台立即重连补查，退出时释放。
- 心跳始终保留，返回 `nagTransportMode`；客户端下一次心跳应用后台模式切换（默认最长 60 秒）。旧服务端缺少字段时按 HEARTBEAT 兼容。
- 已投递依旧表示通知已受理，不表示用户已经看到。Server 酱降级和全屏回应规则保持不变。
- 验收要求：提交后通知/回滚不通知、用户隔离、配置切换、SSE 分帧、重连与取消、重复通知仅显示一个弹框；追加迁移，不修改 V001。


### 2026-09-08 登录身份选择修订

依据用户明确要求（ADR-0028）：登录页选择学员端或督学端，服务端身份校验通过后进入对应首页。移除个人页切换入口及角色切换弹层，督学导航第四项为我的，退出登录位于我的设置；路由拒绝跨端跳转。自动登录保持上次会话身份，退出清除选择，无所选身份时返回登录错误。此规则替代本文旧的登录后切换描述。验收覆盖身份持久化、权限拒绝与退出清理。


### 2026-09-08 记住登录信息与服务器

用户明确追加：登录页提供默认不勾选的「记住用户名和密码」。勾选后提交登录时将输入保存到系统安全存储，重新打开登录页自动回填；取消勾选立即删除对应服务器凭据，失败时明确提示。退出登录只结束会话，保留用户选择记住的表单。用户名密码按规范化服务器 Origin 隔离，禁止写入日志或普通配置。服务器设置保存最近连接成功的地址，去重并可选取回填，仍须测试并保存才切换；切换清除旧 Token，账号密码不跨环境复用。验收：保存与回填、取消删除、环境隔离、服务器历史去重与切换失败保留原地址。


### 2026-09-08 播放与设置交互修订

依据用户明确要求：督学端第四项为「我的」，复用账号设置和退出登录，隐藏学习专属目标和心跳；仍禁止端内身份切换。服务器历史支持删除，仅移除快捷记录，不改变当前连接或删除远端数据。播放器必须接入官方 video_player 的真实视频、位置、暂停、跳转与倍速，不得模拟位置推进；倍速点击弹出 0.75 / 1.0 / 1.25 / 1.5 / 2.0 选项。退出全屏及离页恢复竖屏，系统返回先退出全屏。全屏标题栏显示本地日期时间及真实电量，设备不支持时显示未知。视频前台播放和专注前台运行保持常亮，暂停、结束、后台和离页释放。播放错误提供重试，重试重新取得认证信息；观看时长只累计实际前台非缓冲时间，重开播放器不复用旧 clientSeq 起点。验收覆盖适配器操作、倍速口径、横竖屏恢复、常亮生命周期和服务器删除；真实解码仍需 iPhone / iPad / Android 设备验证。


### 2026-09-08 专注页布局修订

依据用户反馈，专注页以环形进度包围剩余时间，明确显示正在专注、已暂停或准备开始。下方汇总目标时间、已专注时间和完成百分比；内容按可用高度分布，小屏与大字体允许滚动，取消原细条进度和集中上半屏的布局。保持现有操作、凭证、状态机与常亮规则，未开始时不允许调用继续接口。


### 2026-09-08 已批准：专注停止、跳过与右上角提示（ADR-0036）

本修订覆盖原专注状态与百分比展示要求。`ABANDONED` 保留协议和历史数据，界面统一称为「跳过」，仍未完成且保留时长。新增 STOPPED；暂停 / 继续为同一轮，停止后再次开始从零计时、倒计时恢复设定时长，不能拼接已停止轮次达标。总时长保留用于统计，新增 focusAttemptBaseMs 区分本轮；完成判定仅按本轮，专注界面不显示完成百分比；圆环仅表示本轮剩余倒计时。

跳过位于右上角次要入口并二次确认；停止也明确确认其结果。普通反馈统一为右上角安全区内浮动圆角提示，支持关闭和超时消失。状态按钮以服务端为准，终态不显示继续；冲突刷新并展示中文提示。

T09：状态机、单轮完成判断、幂等操作流水，追加 V003 迁移保留旧数据；T26：计时、状态与确认交互、统一轻提示；T32：同步 OpenAPI、原型和追踪。流水复用 todo_progress_events，沿用既有级联和孤儿检查。窄测试覆盖停止重开不抵扣历史时长、跳过取消不写入、重放不重复累计、终态按钮、提示关闭与定位；迁移须真实启动并确认 UP。全量验证交由 CI。


2026-09-08 用户修订：保留倒计时圆环，开始满环、随本轮剩余时间递减、暂停不动、停止后再次开始恢复满环。圆环不是累计完成率，不显示百分比。后续人工验证由用户执行，代理不再操作模拟器或追加验证。


## 2026-09-08 已批准：T06 / T19 课程批量导入增补

依据 ADR-0037，实现 CourseImportService、EmbyImportAdminController、共用 EmbyCourseImport.vue 与 EmbyCourseImportDialog.vue，Emby 同步页与课程库均通过按钮打开弹窗；保存绑定不展开列表，弹窗内完成选择、导入与结果查看。补齐无关键词发现、当前页封面/准确课时数、搜索分页、多选与全选、逐项导入结果、失败重试、重复幂等与归档保护。远端读取在事务外、短事务落完整快照；不改数据库。窄验证覆盖绑定范围、分页失败、重复导入和归档、写事务边界、后台 DTO；前端构建、真实启动健康检查，全量验证交 CI。提交主题：feat: 支持从绑定媒体库批量导入课程。


### 2026-09-08 播放与退出上报缺陷修复（T08 / T25）

静态媒体流只以 Emby Item ID 定位，不推测 MediaSourceId；保持 Range 流式透传。播放器退出用 PAUSE 补报；服务端将旧 EXIT 排队请求兼容为 PAUSE，未知事件在写库前返回 TODO_PROGRESS_EVENT_INVALID，避免触发数据库 CHECK 约束。无数据库迁移。回归验证媒体参数、206 与 Range 透传、退出事件兼容及非法事件不写入。


## 2026-09-08 已批准：课时防重与历史顺延确认

R06 / R15 → T07 / T24 → 原型 2-2 → ADR-0038：逐项跳过同日同目标重复，不同目标先确认复用；批量添加先预览历史未完成项，确认后顺延原 Todo 并保留所有记录；多历史项逐课时单选，可跳过。服务端提交时复核，返回新增/顺延/复用/跳过汇总；复用或顺延目标取较高值，保留已看进度。原创建接口同步防重，不删除已有数据。验收与窄测试见 ADR-0038。

### 2026-09-08 播放器显示修订（T25）

- 播放、暂停、前后 10 秒使用完整单一图标，中央按钮与上下操作栏使用不透明深色底。
- 倍速在视频内部的深色面板选择，横竖屏共用；附件、备注和完成操作按内容宽度排列，窄屏整按钮换行。
- iOS 改用官方 video_player 的原生视图路径，以排查模拟器有声绿屏；进度和观看时长口径不变。绿屏是否解决仍须用原视频在模拟器复验，并保留真机播放验收。

### 2026-09-08 media_kit 小范围验证（T25 / ADR-0039）

本次经批准将默认播放内核改为 media_kit，覆盖此前指定官方 video_player 的技术选型。界面、上报协议、时长、常亮与生命周期口径不变；旧适配器可通过构建参数回退对照。新增适配器窄测试并进行 iOS 模拟器构建，原绿屏视频实播和三类真机验证仍是独立验收项。

### 播放布局与刷新修正

竖屏画面使用独立 16:9 区域并等比容纳原视频，44pt 标题栏和紧凑时间轴工具栏位于画面外，不遮挡课件；全屏画面使用剩余空间。中央控制按钮保持不透明深色底，播放三秒后隐藏，点击画面恢复。错误卡片与播放按钮互斥。位置保留原始精度，界面通知每 250ms 合并一次；播放位置继续推进时清除旧错误。以上不改变进度上报、观看时长与服务端达标口径。

### 片尾处理补充

原生内核明确报告 ended 后，对齐最终位置、清除错误并停止缓冲；不允许后续 EOF 日志覆盖片尾状态，重新跳转后恢复正常错误检测。即使暂停事件先到，片尾也单独补报一次。仅对内核确认片尾且 Emby 时长高出实际位置不超过两秒的尾差作校正，不增加观看时长；服务端继续裁决达标。没有片尾事件时不得根据显示时间或 99% 推测完成。

### iOS 试用回退与命令解耦

iOS 模拟器实测出现无声、操作失效和重复加载失败，当前默认回退 video_player 的 AVPlayer 原生视图（非之前纹理模式），保留新 UI；显式 PLAYBACK_ENGINE=media_kit 仍可对照。其他平台保持原试用配置。AVPlayer 新建前等待旧实例释放；播放命令不等待进度网络上报，重试不依赖故障内核暂停成功。上游同类无声报告：https://github.com/media-kit/media-kit/issues/1100 ，该报告只是选型参考，不代表本次根因已获实播证明。

### 启动产物一致性修复

19:59 模拟器新进程日志仍创建 media_kit，说明此前回退没有进入实际运行链路。run.sh ios 改为先构建再通过 --use-application-binary 运行该 App 产物，显式传递 PLAYBACK_ENGINE（默认 video_player）；只打印内核名称，不记录媒体地址或凭据。原生声音和操作仍以实播验收为准。

### 移动端播放器浮层修订（T25）

按用户提供的哔哩哔哩手机端横竖屏截图修订 T25。竖屏为 16:9，底部同一行依次展示播放、细进度条、时间和全屏图标，顶部右侧仅保留倍速入口，快退快进仅在横屏操作行展示；横屏顶部展示标题与设备状态，底部依次为时间、通栏进度条、操作行（左侧播放和快退快进，右侧倍速与退出全屏）。不设置中央大圆按钮或工具胶囊背景；白色图标与文字共享深色遮罩，点击热区至少 44pt。播放三秒后整层隐藏，点击画面恢复，暂停保持可操作。倍速竖屏在底部以五档文字横排展开、横屏在 240pt 右侧面板以文字列表展开；不使用色块、边框或勾选图标，选中项使用强调色及下划线，操作期间不自动隐藏。附件、备注、目标和完成入口留在视频下方。不增加弹幕、社交或投屏能力，不改变播放内核、进度和真实观看时长口径。全屏画面黑底铺满屏幕，仅操作层避开刘海与系统手势区域；退出时恢复系统栏。视频时间轴不展示额外目标刻度，目标保留在下方进度卡。绿屏仍需独立排查。

### T25 加载等待收敛

视频加载全链路最多等待 30 秒，恢复位置最多等待 8 秒；旧原生播放器释放等待最多 5 秒。加载期间保留返回入口，原生画面初始化完成后先挂载再恢复位置。超时释放本轮播放器并提供重新加载入口，退出或超时后的迟到结果不得覆盖当前页面。此修订不改变进度与观看时长口径；实际播放由用户验证。

### T25 双击分区与倍速面板

倍速面板直接显示和关闭，无位移或渐变动画。横竖屏均按播放器实际宽度分成三等份：左侧双击快退 10 秒，中间双击切换播放/暂停，右侧双击快进 10 秒；单击仍切换控制层。双击完成后才执行动作，加载、错误和倍速选择期间不响应画面双击；按钮和进度条保留各自操作。跳转继续沿用现有边界限制及进度上报规则。

### T25 操作即时反馈

画面双击识别层与控制按钮分层，倍速入口不参与双击等待。选中倍速立即更新并收起面板，原生倍速命令异步按序执行，失败恢复已确认的倍速并提示。双击左右展示箭头及快退/快进 10 秒，中间展示播放/暂停，反馈立即出现并在 650ms 内放大淡出。暂停时中央常驻暂停图标；加载、跳转及缓冲期间显示转圈与对应文字，恢复后消失。跳转等待最多 8 秒，不计入观看时长；倍速面板继续无进出动画。

### T25 中央播放控件修订

中央播放/暂停图标可单击切换状态，暂停时常驻播放图标用于继续播放，播放时中央反馈显示暂停动作；点击不等待画面双击判断。全屏底栏移除快进快退按钮，左右双击仍执行 ±10 秒。左右动作反馈不拦截触摸。

## 已批准变更：当前 App 页面与活动

见 [ADR-0040](../adr/0040-current-app-activity.md)。扩展心跳单行快照，在后台与督学端显示页面、视频/专注状态及更新时间，涉及 T12 / T17 / T30 / T31。2026-09-08 已获用户批准，按 ADR-0040 扩展协议与原型。新增 V004 迁移，只维护单行快照；位置变化不刷新有效操作。展示页面每 15 秒刷新，后台或离页停止读取。

### 2026-09-08 设置页用户信息固定

按用户明确要求修订 T29 / 原型 6-1：我的/设置顶部用户信息固定，心跳状态及以下内容独立滚动；督学端复用相同固定用户区，保留其隐藏心跳的规则。下拉刷新、账号设置与退出登录行为不变。窄测试验证滚动前后用户区位置不变、心跳区随内容滚动。
