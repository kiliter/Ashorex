# ADR-0032：废弃 V1 业务代码，按保留白名单重建代码基线

- 状态：提议（待人工批准）
- 日期：2026-09-07
- 相关：与 ADR-0031（数据库 baseline 重建）配套；取代原实施计划中「逐包删除」的方案

## 背景

原实施计划把 V1 代码清理设计为逐包删除（T01 一次删 7 个包，或拆成 Flutter / AI 域 / 学习闭环簇三步）。评估依赖后发现两个问题：

1. **待删包构成强连通簇**，无法安全分批：

```text
planning 定义 PlanProgressPort / VideoTaskRequirementPort / DayEndPlanCloser / ActiveLearningCloser
  ← learning.WatchSessionPlanCloser 实现
  ← focus.FocusSessionPlanCloser 实现
  ← quiz.QuizRequirementAdapter 实现
  → 调用 debt.DefaultDebtService
reporting 依赖以上全部
```

分批删除必然产生编译不通过的中间态，需要临时桩代码顶着，既违反「不做临时脚手架」，也是纯浪费。

2. **V1 与 V2 的领域模型冲突是根本性的**，不是增量差异：`Todo` 取代计划 + 欠债 + 答题 + 模拟考试四个域；进度模型从「可信最大位置 + 票据 + 验活」变为「最远位置 + 前台时长」；`media_items` 抽象为 `learning_resources`；新增督学、催办、归档三个域。业务层几乎没有可平移的代码。

用户据此指示：如果冲突较大就直接废弃历史全部代码做更彻底的重构，只保留可复用的部分。

## 决策

放弃「逐包删除」，改为**按白名单重建代码基线**：一次性清空业务层，只保留下列基础设施；保留项若与 V2 模型不符则在各自 Task 中改写，而不是在清理阶段修补。

审查方式随之反转：审「保留了什么」（约 30 个文件）而不是审「删了什么」（数百个文件）。

### 服务端保留白名单

| 包 | 保留 | 理由 |
|---|---|---|
| `common.config` | `SqliteConfiguration`、`SecurityConfiguration`、`ApplicationConfiguration`、`OpenApiConfiguration` | PRAGMA、Session/CSRF、Clock 注入、文档配置与 V2 一致 |
| `common.api` | `ApiExceptionHandler`、`BusinessException`、`RequestIdFilter`、`RequestLoggingInterceptor` | Problem Details、请求 ID、脱敏日志 |
| `common.auth` | `CurrentUser`、`CurrentUserArgumentResolver` | V2 扩展 roles 后继续使用 |
| `common` | `IdGenerator`、`UuidIdGenerator` | UUID 约定不变 |
| `common.integration` | `RuntimeIntegrationSettings*`、`IntegrationSettingsProvider` | V2 精简为 Emby + Server 酱 + features，结构复用 |
| `identity` | `JwtService`、`AuthService`、`AuthController`、`UserRepository`、`JdbcUserRepository`、`User` | 登录 / 刷新 / 登出流程不变；`users` 字段变更在 T03 改写 |
| `media.emby` | `EmbyClient`、`EmbyProperties`、`EmbyGateway`、`EmbyDtos`、`EmbySourceFingerprint`、`EmbyStreamProxy`、`EmbyHealthService` | V2 仍以 Emby 为唯一媒体源；DTO 在 T06 扩展 Genres / Tags / People |
| `admin` | `AdminLoginController`、`AdminEntryController`、`HealthAdminController`、`AdminDisplayFormatter`、`OperationsHealthService` | 后台登录与健康骨架；`OperationsHealthService` 在 T17 去掉 ASR/LLM 项 |
| `resources/templates/admin` | `login.html`、`health.html`、`fragments.html` | 布局与登录页；`fragments.html` 的导航在 T17 重写 |
| 根 | `ShanganApplication` | 启动类 |

服务端删除：`debt`、`quiz`、`planning`、`reporting`、`focus`、`ai`（含 `ai.content`）、`dashboard` 全部；`learning` 全部（V2 的播放代理在 T25 依据 `EmbyStreamProxy` 重建）；`catalog` 与 `exam` 全部（V2 在 T04 / T05 重建为 `goal` 与新 `catalog`）；`admin` 除白名单外全部；`templates/admin` 除白名单外 14 个模板。

### Flutter 保留白名单

| 目录 | 保留 | 理由 |
|---|---|---|
| `lib/app` | `app.dart`、`bootstrap.dart`、`application_bootstrap.dart` | 启动与依赖装配；`router.dart` 在 T21 重写 |
| `lib/core/api` | `api_client.dart`、`api_exception.dart` | Dio 封装与错误映射 |
| `lib/core/auth` | `auth_controller.dart`、`auth_repository.dart` | 认证状态机 |
| `lib/core/storage` | `token_store.dart` | Token 存储 |
| `lib/core/config` | 全部 4 个文件 | 可配置服务端地址（ADR-0005 仍有效） |
| `lib/core/theme` | `shangan_theme.dart` | 色板与组件主题；V2 在 T21 补督学端赭色与播放器 token |
| `lib/core/device` | `screen_wake_lock.dart` | 播放常亮 |
| `lib/core/widgets` | `shangan_ui.dart` 中 10 个通用组件、`shangan_markdown.dart` | 见下 |
| `lib/features/auth` | 全部 | 登录页与连接恢复页 |

`shangan_ui.dart` 逐组件裁剪：

| 保留 | 删除（V1 专属） |
|---|---|
| `ShanganEyebrow`、`ShanganStatusTag`、`ShanganSurface`、`ShanganNotice`、`ShanganNavRow`、`ShanganMetricGrid`、`ShanganProgress`、`ShanganCountUpPercent`、`ShanganIdleScrollbar`、`ShanganLoading` | `ShanganWatchProgress`（可信进度）、`ShanganTrustScale` + `_TrustScalePainter` + `_Legend`（可信刻度）、`ShanganIdleMotion`（伴学动效）、`ShanganCompletionHero` + `_CompletionRingPainter`（审判礼花）、`_StripedProgressPainter`（欠债条纹） |

Flutter 删除：`lib/features` 除 `auth` 外全部（`debt`、`quiz`、`planning`、`reporting`、`companion`、`focus`、`catalog`、`dashboard`、`exam`、`player`、`profile`）。V2 的 `player`、`catalog`、`profile` 在各自 Task 中重建，不做平移。

### 测试基线

- 服务端保留 `common`、`identity`、`media/emby` 下的测试；删除 `acceptance`、`ai`、`catalog`、`dashboard`、`debt`、`exam`、`focus`、`learning`、`planning`、`quiz`、`reporting`、`admin`、`api` 下的 V1 测试。
- Flutter 保留 `test/core/api`、`test/core/auth`、`test/core/config`、`test/core/theme`、`test/features/auth`；删除 `test/features` 其余全部（含遗留的 `ai_chat`、`companion`）与 `test/app/router_test`（T21 重写）、`test/core/widgets` 中针对被删组件的用例。

### 不动的部分

`infra/`、`run.sh`、`test.sh`、`Makefile`、CI workflow、`.sdkmanrc`、FVM 配置全部保留，只在 T01 中删除 ASR / LLM / OpenRouter 相关环境变量。

## 后果

正面：

- 没有编译不通过的中间态，一个 Task 完成清理。
- 审查对象是一份约 30 项的白名单，比审查数百个删除文件更可靠。
- 后续 Task 在干净基线上重建，不需要迁就 V1 抽象（例如 `PlanProgressPort` 这类为旧模型而设的端口）。

负面与代价：

- 单次 diff 极大（删除数万行），无法逐行审查，只能靠白名单 + 编译 + 静态分析 + 后续 Task 的测试兜底。
- 保留项与 V2 模型不完全匹配（`users` 表字段、`EmbyDtos` 字段、`OperationsHealthService` 检查项、`fragments.html` 导航），清理后会有一段「能编译但功能不完整」的状态，直到对应 Task 完成。这是可接受的，因为 V2 本身还未成形。
- 如果白名单漏保留了某个真正有用的实现，只能从 Git 历史里取回。保留分支或标签作为兜底。

## 替代方案

1. **逐包删除（原 T01）**。否决：强连通簇导致中间态编译失败，需要临时桩代码。
2. **拆成 Flutter / AI 域 / 学习闭环簇三步**。否决：切割线成立，但三次改动仍是同一件事，且总 diff 不变；用户明确倾向彻底重构。
3. **新建仓库或新建模块，V1 代码留在原处**。否决：会长期并存两套配置、两套 CI、两套部署产物，比一次清空代价更高。
4. **保留 V1 代码但标记废弃，逐步替换**。否决：废弃代码仍会参与编译与测试，且新旧模型共存会让每个 Task 都要处理兼容分支。

## 前置条件

在 T01 执行前必须完成：

1. 当前 `main` 打标签（例如 `v1-final`）并推送，作为可回溯的 V1 代码基线。
2. 现有 `study.db` 已按 ADR-0031 要求备份归档。
