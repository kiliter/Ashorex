# 上岸 V1 产品与技术设计规范

**文档状态：** 已冻结，可进入实现
**版本：** V1.0
**日期：** 2026-08-27
**目标平台：** iOS
**工作仓库名：** `shangan`
**产品名：** 上岸

---

## 1. 文档目的

本文档定义“上岸”V1 的产品边界、业务规则、系统架构、模块职责、数据模型、接口原则、媒体播放方案、AI 方案、测试要求、部署方式和验收标准。

实现人员不需要依赖此前聊天记录。聊天中出现但未写入本文档的想法，均不属于 V1。

---

## 2. 产品定位

“上岸”不是在线教育平台，也不是通用播放器，而是一款面向考研、考公等长期备考人群的学习监督工具。

核心闭环：

```text
考试目标
  ↓
考试倒计时与进度压力
  ↓
制定每日计划
  ↓
锁定计划
  ↓
视频学习 / 课后答题 / 专注计时
  ↓
正常完成，或主动开摆
  ↓
未完成任务形成学习欠债
  ↓
每日晚间审判与每周复盘
  ↓
第二天继续还债
```

V1 的价值判断标准：

1. 用户是否更难“骗自己已经学了”。
2. 用户是否清楚今天完成了什么、欠了什么。
3. 用户是否可以低成本持续使用。
4. 系统是否足够简单，能由单机 SQLite 和一个后端实例稳定运行。

---

## 3. V1 范围

### 3.1 本期必须实现

#### iOS 学习端

- 账号登录。
- 设置一个当前考试目标。
- 首页展示考试倒计时、今日计划、历史欠债、课程进度压力和学习数据。
- 浏览课程与 Emby 剧集。
- 创建并锁定每日计划。
- 观看 Emby 视频。
- 禁止跳到尚未验证观看的区间；允许回看已经验证的区间。
- 记录可信观看时间与可信最大进度。
- 视频中随机验活。
- 视频完成后答题。
- 番茄钟、练习计时和模拟考试计时。
- 开摆，且剩余计划立即转为学习欠债。
- 正常日终未完成的计划同样转为学习欠债。
- 查看学习欠债。
- 每日晚间审判。
- 每周学习报表。
- 底部独立 AI Tab。
- 视频播放页 AI 问答弹层。

#### 服务端

- 模块化单体后端。
- SQLite 主数据库。
- Emby API 集成、课程同步、视频播放代理。
- 题目配置与答题记录。
- 可信观看进度校验。
- 日计划、锁定、开摆、欠债、报表规则。
- 视频音频转写、分段摘要、全局摘要。
- 只读 AI 问答。
- MCP 联网搜索。
- 内部管理后台。
- 数据库迁移、健康检查、日志、备份和恢复。

#### 管理后台

- 管理员登录。
- 创建和禁用用户。
- 查看 Emby 连接状态。
- 绑定 Emby 剧集或目录为课程。
- 手动同步课程。
- 启用、禁用和排序视频。
- 为视频配置单选题和判断题。
- 查看并重试转写任务。
- 查看基础运行状态。

### 3.2 明确不做

- Android 客户端。
- Windows 或 macOS 客户端。
- PC 学习 Web。
- 离线视频下载和离线学习。
- 付费、订阅、商城、课程售卖。
- 直播。
- DRM。
- 排行榜、社交、研友、监督人。
- 推送通知和 Live Activity。
- AI 修改计划、欠债、考试目标或任何业务数据。
- AI 主动催学、AI 审判、AI 自动排计划。
- 多智能体。
- 向量数据库。
- 微服务、Redis、Kafka、对象存储和 Kubernetes。
- 主观题 AI 批改。
- App Store 正式发布自动化；V1 首先以真机和 TestFlight 验收。

---

## 4. 核心业务规则

### 4.1 考试目标

每个用户 V1 只能有一个活动考试目标。

字段：

- 考试名称。
- 考试日期。
- 计划完成课程日期。
- 复习缓冲天数，默认 14 天。
- 用户时区，默认 `Asia/Shanghai`。
- 参与进度计算的课程。

系统计算：

- 距离考试天数。
- 距离计划完成日天数。
- 已完成课程数和剩余课程数。
- 最近 7 天平均完成速度。
- 按当前速度预计完成日期。
- 为按期完成每天至少需要完成的课程量。

考试倒计时和压力信息只读，不自动修改计划。

### 4.2 每日计划

每日计划状态：

```text
DRAFT → LOCKED → COMPLETED
                 ↘ ABANDONED
                 ↘ CLOSED_WITH_DEBT
```

规则：

- DRAFT 可新增、删除和调整任务。
- 用户点击“开始今天”后进入 LOCKED。
- LOCKED 后，不允许无痕增加、删除、调整任务。
- 当全部任务完成，状态变为 COMPLETED。
- 点击开摆，状态立即变为 ABANDONED。
- 到达用户日终时间仍未完成，状态变为 CLOSED_WITH_DEBT。
- ABANDONED 和 CLOSED_WITH_DEBT 都必须生成欠债。
- 日终关闭和欠债生成必须幂等，重复执行不能生成重复欠债。

计划任务类型：

- `VIDEO`：观看指定视频，并完成必答课后题。
- `FOCUS`：专注计时。
- `QUIZ`：独立答题任务，V1 可暂不在客户端单独创建，但模型保留。
- `DEBT_REPAYMENT`：偿还历史欠债，内部仍引用原始任务类型。

### 4.3 学习欠债

欠债必须记录“欠了什么”，不能只记录分钟数。

每笔欠债包括：

- 原始计划任务。
- 类型。
- 关联视频、题目或专注任务。
- 原始预计时长。
- 剩余时长。
- 产生原因。
- 产生日期。
- 当前状态。

欠债状态：

```text
OPEN → PARTIALLY_REPAID → PAID
                    ↘ WAIVED（仅管理员）
```

欠债类型：

- `VIDEO_WATCH`：视频尚未可信观看完。
- `QUIZ`：视频已要求课后题，但尚未提交完整答卷。
- `FOCUS`：专注任务未达到计划时长。

规则：

- 开摆时，所有未完成任务按未满足的组成部分生成欠债。
- 正常日终仍未完成时，按同样规则生成欠债。
- VIDEO 任务可以同时生成一笔 `VIDEO_WATCH` 欠债和一笔 `QUIZ` 欠债。
- 视频债务的剩余量以视频可信进度计算。
- QUIZ 欠债使用 600 秒作为 V1 计划压力估值；提交完整答卷即结清，不按真实答题秒数扣减。
- 专注债务按计划时长减去已完成时长计算。
- `DEBT_REPAYMENT` 任务再次未完成时，不创建嵌套欠债，只保留并更新原欠债。
- 同一 `source_plan_item_id + debt_type` 只能生成一笔欠债。
- 后续可信观看同一视频会自动减少对应 `VIDEO_WATCH` 欠债；完成同一视频的完整答题会自动结清对应 `QUIZ` 欠债。
- 用户创建下一日计划时，未还欠债显示在新任务之前，并默认推荐加入计划，但不自动锁定。
- 完成关联的还债任务后，对应欠债减少或结清。
- V1 客户端不能直接核销欠债。

### 4.4 开摆

开摆仅对 LOCKED 状态的当日计划可用。

确认页面必须展示：

- 剩余任务。
- 预计新增欠债。
- 开摆原因。
- 明确说明操作后果。

确认后：

1. 结束当日所有进行中的学习会话。
2. 将计划状态改为 ABANDONED。
3. 生成欠债。
4. 生成或刷新当日晚间审判。
5. 记录开摆原因和时间。

V1 不提供“撤销开摆”。

### 4.5 视频可信观看

Emby 只负责媒体，不负责学习真实性。

系统有两套独立状态：

- Emby 播放状态。
- 上岸可信学习进度。

上岸进度永远是业务真相。

规则：

- 用户不能跳到 `maxVerifiedPosition` 之后。
- 用户可以回看 `0..maxVerifiedPosition`。
- App 进入后台时自动暂停。
- 网络中断导致心跳失败时，停止累计可信时间；连续失败后暂停播放。
- 心跳默认每 10 秒发送一次。
- 服务端校验时间间隔、位置增量、播放状态、前台状态和序号。
- 服务端不接受明显跳跃的进度。
- 不保存每个心跳事件，只保存会话聚合信息，避免无意义数据膨胀。
- 视频完成阈值：

```text
duration - min(30 秒, duration × 2%)
```

达到阈值后，视频观看状态记为完成。

### 4.6 随机验活

随机验活由服务端驱动。

流程：

1. 创建观看会话时，服务端生成下一次验活所需可信观看秒数。
2. 心跳达到阈值时，服务端返回 `aliveCheckRequired=true`。
3. App 立即暂停并弹出验活对话框。
4. 用户必须在 60 秒内确认。
5. 超时后保持暂停，并记录一次失败。
6. 验活等待期间不累计可信时间。
7. 确认后服务端生成下一次验活阈值。

监督等级 V1 提供：

- 关闭。
- 普通：40 到 60 分钟。
- 严格：20 到 40 分钟。
- 拷打：10 到 25 分钟。

默认普通。

不使用摄像头、人脸识别或麦克风验活。

### 4.7 课后答题

V1 题型：

- 单选题。
- 判断题。

规则：

- 视频可信观看完成后才可获取题目。
- 每个视频可配置 0 到多道题。
- 有题目时，计划中的 VIDEO 任务只有在至少提交一次完整答题后才算完成。
- 没有题目时，视频观看完成即算完成。
- V1 不以分数锁定下一节视频。
- 保存每次答题、选项、正误、耗时和解释。
- 允许重复答题，报表默认展示最新一次和历史最佳一次。

### 4.8 专注计时

类型：

- `POMODORO`。
- `PRACTICE`。
- `MOCK_EXAM`。

规则：

- 服务端记录开始、暂停、继续和结束时间。
- iOS 根据服务端返回的时间戳渲染倒计时，不能只依赖本地递减。
- App 切后台后计时是否继续由类型决定：
  - POMODORO、PRACTICE：继续计时。
  - MOCK_EXAM：继续计时。
- 用户主动取消时不计为完成。
- 计时可绑定到计划任务或视频后的“小练一笔”。

### 4.9 日报、周报与晚间审判

所有报表由规则和 SQL 聚合生成，不使用 AI。

日报指标：

- 计划时长。
- 有效视频学习时长。
- 专注时长。
- 完成任务数和完成率。
- 视频完成数。
- 答题数和正确率。
- 验活失败数。
- 是否开摆。
- 新增欠债。
- 历史未还欠债。

晚间审判由规则模板生成，示例规则：

- 完成率大于等于 90%：肯定完成情况。
- 完成率 60% 到 90%：指出主要未完成项。
- 完成率低于 60%：明确新增欠债和进度风险。
- 开摆：指出开摆时间、原因和新增欠债。
- 连续 3 天新增欠债：提示债务连续增长。

周报指标：

- 每日有效学习趋势。
- 视频、专注、答题总量。
- 正确率。
- 计划完成率。
- 新增和偿还欠债。
- 开摆次数。
- 验活失败次数。
- 与上周对比。

### 4.10 AI 范围

AI 只有两个入口。

#### 首页 AI Tab

能力：

- 常规问答。
- 通过只读工具查询用户的今日计划、欠债、考试进度和报表。
- 通过 MCP 执行联网搜索。
- 流式返回。
- 返回可用的来源信息。

禁止：

- 新增、修改或删除任何业务数据。
- 调用写工具。
- 主动弹出。
- 自动调整计划。
- 自动开摆或核销欠债。

#### 视频播放页 AI

能力：

- 当前视频问答。
- 整体视频摘要。
- 基于全部转写所生成的全局摘要和分段摘要回答。
- 检索相关转写片段。
- 优先加入当前播放时间附近的转写。
- 常规问答。
- MCP 联网搜索。

回答应尽量返回：

- 视频内时间戳引用。
- 联网搜索来源。

AI 不直接接收每个长视频的全部原始文本。后端采用分段转写、分段摘要、全局摘要和全文检索，在不超出上下文窗口的前提下构建等价的完整视频上下文。

---

## 5. 总体架构

```text
┌──────────────────────────────────────┐
│             iOS Flutter App          │
│                                      │
│ 首页 / 学习 / AI / 数据 / 我的       │
│ 视频播放器 / 题目 / 计时 / 报表       │
└───────────────┬──────────────────────┘
                │ HTTPS REST + SSE
                ▼
┌──────────────────────────────────────┐
│       Spring Boot 模块化单体          │
│                                      │
│ Identity  Exam      Catalog          │
│ Planning  Debt      Learning         │
│ Quiz      Focus     Reporting        │
│ AI        Emby      Admin            │
└───────┬─────────────┬───────────────┘
        │             │
        ▼             ▼
     SQLite          Emby
        │             │
        │             └─ 视频 / 剧集 / 封面 / 转码
        │
        └─ 业务数据 / 转写 / 摘要 / 聊天记录
                │
                ▼
          LLM / ASR / MCP
```

部署原则：

- 一个后端实例。
- 一个本机 SQLite 文件。
- SQLite 文件不得放在 NFS 或远程共享盘。
- Emby 可以是同机或独立 NAS。
- 所有外部密钥只保存在服务端环境变量中。
- iOS 不接触 Emby API Key、LLM Key、ASR Key 或 MCP 凭据。

---

## 6. 技术基线

截至 2026-08-27 的冻结基线：

| 区域 | 选择 |
|---|---|
| iOS 客户端 | Flutter 3.44.7，使用 FVM 固定版本 |
| iOS 最低版本 | iOS 16 |
| Dart 状态管理 | flutter_riverpod 3.0.2，不使用代码生成 |
| 路由 | go_router 17.5.0 |
| HTTP | Dio 5.11.0 |
| Token 存储 | flutter_secure_storage 11.0.0 |
| 视频 | Flutter 官方 video_player 2.14.0 |
| AI 聊天 UI | flutter_chat_ui 2.11.0 |
| 后端 | Java 21 LTS + Spring Boot 4.1.1 |
| Web 模型 | Spring MVC + Virtual Threads |
| 数据访问 | Spring JdbcClient / NamedParameterJdbcTemplate |
| 数据库 | SQLite + WAL，sqlite-jdbc 3.53.4.0 |
| 迁移 | Flyway Core 13.3.0 + flyway-database-nc-sqlite 13.3.0 |
| Java 格式化 | Spotless Maven Plugin 3.10.0 + Google Java Format |
| AI 框架 | LangChain4j BOM 1.19.0；由 BOM 管理的集成模块可能为 1.19.0-beta29 |
| AI Agent 方式 | AI Services + ToolProvider，不使用实验性 multi-agent 模块 |
| MCP | LangChain4j MCP Client，优先 Streamable HTTP |
| 音频处理 | FFmpeg |
| 管理后台 | Spring MVC + Thymeleaf + 少量原生 JavaScript |
| API 文档 | springdoc-openapi 3.1.0 |
| 构建 | Maven Wrapper、FVM |
| 部署 | Docker Compose 或 systemd + Caddy |

版本规则：

- 核心框架使用上述精确版本；如初始化时无法解析，先查明原因，不得静默换版本。
- Flutter 提交 `pubspec.lock`。
- Maven 使用 BOM 管理依赖；Flyway Core 与 SQLite 数据库模块必须保持完全相同的版本。
- 禁止使用 milestone、RC 和 SNAPSHOT。
- LangChain4j 使用低层依赖与程序化 AI Services；不使用 `langchain4j-agentic` 实验模块，也不依赖自动扫描注册 Agent。
- 每次升级只做一个依赖族，并运行完整测试。
- 冻结版本优先选择已经稳定发布一段时间的版本；不因新主版本刚发布就自动追新。

---

## 7. 仓库组织

采用单仓库、两个应用、按功能组织。

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
│   ├── specs/
│   │   └── 2026-08-27-shangan-v1-design.md
│   ├── plans/
│   │   └── 2026-08-27-shangan-v1-implementation-plan.md
│   ├── traceability/
│   │   └── 2026-08-27-shangan-v1-traceability.md
│   ├── adr/
│   ├── api/
│   │   └── openapi.yaml
│   └── runbooks/
│       └── backup-restore.md
├── apps/
│   ├── server/
│   │   ├── pom.xml
│   │   ├── mvnw
│   │   ├── mvnw.cmd
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/com/shangan/
│   │       │   └── resources/
│   │       │       ├── application.yml
│   │       │       ├── db/migration/
│   │       │       ├── templates/admin/
│   │       │       └── static/admin/
│   │       └── test/
│   └── ios/
│       ├── .fvmrc
│       ├── pubspec.yaml
│       ├── pubspec.lock
│       ├── ios/
│       ├── lib/
│       ├── test/
│       └── integration_test/
└── infra/
    ├── compose.yml
    ├── Caddyfile
    ├── docker/
    │   └── server.Dockerfile
    └── scripts/
        ├── backup.sh
        └── restore.sh
```

原则：

- V1 不拆 Maven 多模块。
- 服务端单模块，内部按业务功能分包。
- Flutter 按 Feature First 组织。
- 管理后台与后端同包部署，不单独创建 Vue 项目。
- API 统一 `/api/v1`。
- 将来 Android 复用 Flutter 工程。
- 将来 PC Web 直接复用 API，不复用 iOS UI。

---

## 8. 服务端模块组织

根包：

```text
com.shangan
├── common
├── identity
├── exam
├── catalog
├── planning
├── debt
├── learning
├── quiz
├── focus
├── reporting
├── ai
├── media
│   └── emby
└── admin
```

每个模块按需要使用：

```text
<feature>/
├── api/
├── application/
├── domain/
└── infrastructure/
```

职责：

| 模块 | 职责 |
|---|---|
| `common` | 错误模型、认证上下文、时钟、ID、分页、配置 |
| `identity` | 用户、密码、登录、刷新 Token、管理员会话 |
| `exam` | 考试目标、课程绑定、进度压力计算 |
| `catalog` | 课程和视频本地快照 |
| `planning` | 每日计划、任务和锁定状态机 |
| `debt` | 欠债生成、偿还、查询 |
| `learning` | 观看会话、可信进度、验活、播放票据 |
| `quiz` | 题目、选项、答题记录 |
| `focus` | 番茄钟和练习计时 |
| `reporting` | 日报、周报、晚间审判 |
| `ai` | 对话、流式输出、只读工具、转写与摘要 |
| `media.emby` | Emby 客户端、同步、播放代理 |
| `admin` | 内部管理页面 |

模块边界：

- Controller 不直接访问数据库。
- 应用服务编排事务。
- Repository 只负责持久化。
- 跨模块调用只通过明确的应用接口。
- 不创建“万能 Service”“万能 Util”或超大 Controller。
- 所有时间逻辑注入 `java.time.Clock`，便于测试。

---

## 9. SQLite 设计

启动配置：

```sql
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
PRAGMA synchronous = NORMAL;
```

连接池：

- Hikari 最大连接数 4。
- 单实例。
- 禁止多个服务实例同时连接同一数据库文件。
- 所有写事务尽量短。
- 心跳更新使用单条事务。
- 报表查询不得长期持有写锁。

标识和时间：

- 主键使用 UUID 字符串。
- 数据库时间使用 UTC Epoch Milliseconds。
- API 时间使用 ISO-8601 UTC。
- 用户日期边界按用户时区计算。

数据库文件：

```text
./data/study.db
```

转写文本可以先存 SQLite；不单独引入对象存储。

---

## 10. 数据模型

以下是 V1 必须存在的逻辑表。字段可在实现时补充版本号和审计字段，但不能改变核心关系。

### 10.1 身份

#### `users`

- `id`
- `username`
- `password_hash`
- `display_name`
- `role`：USER / ADMIN
- `timezone`
- `alive_check_level`
- `day_end_local_time`，默认 `23:59`
- `enabled`
- `created_at`
- `updated_at`

#### `refresh_tokens`

- `id`
- `user_id`
- `token_hash`
- `expires_at`
- `revoked_at`
- `created_at`

### 10.2 考试与课程

#### `exam_goals`

- `id`
- `user_id`
- `name`
- `exam_date`
- `target_finish_date`
- `review_buffer_days`
- `active`
- `created_at`
- `updated_at`

#### `courses`

- `id`
- `name`
- `description`
- `emby_parent_item_id`
- `enabled`
- `sort_order`
- `last_synced_at`
- `created_at`
- `updated_at`

#### `exam_goal_courses`

- `exam_goal_id`
- `course_id`

#### `media_items`

- `id`
- `course_id`
- `emby_item_id`
- `title`
- `overview`
- `duration_ms`
- `series_name`
- `season_number`
- `episode_number`
- `image_tag`
- `enabled`
- `sort_order`
- `source_updated_at`
- `created_at`
- `updated_at`

唯一约束：`emby_item_id`。

### 10.3 每日计划

#### `daily_plans`

- `id`
- `user_id`
- `plan_date`
- `status`
- `locked_at`
- `closed_at`
- `created_at`
- `updated_at`

唯一约束：`user_id + plan_date`。

#### `daily_plan_items`

- `id`
- `plan_id`
- `item_type`
- `title`
- `media_item_id`
- `debt_id`
- `planned_seconds`
- `completed_seconds`
- `watch_completed`
- `quiz_required`，在计划锁定时快照
- `quiz_completed`
- `status`
- `sort_order`
- `completed_at`
- `created_at`
- `updated_at`

#### `plan_abandonments`

- `id`
- `plan_id`
- `user_id`
- `reason_code`
- `reason_text`
- `remaining_seconds`
- `created_at`

### 10.4 欠债

#### `learning_debts`

- `id`
- `user_id`
- `source_plan_item_id`
- `debt_type`
- `media_item_id`
- `title`
- `original_seconds`
- `remaining_seconds`
- `baseline_completed_seconds`，仅 VIDEO_WATCH 使用
- `status`
- `reason`
- `opened_on`
- `paid_at`
- `created_at`
- `updated_at`

唯一约束：`source_plan_item_id + debt_type`。

#### `debt_repayments`

- `id`
- `debt_id`
- `plan_item_id`
- `repaid_seconds`
- `repayment_source`：PLAN_ITEM / DIRECT_VIDEO / DIRECT_QUIZ / ADMIN
- `created_at`

### 10.5 视频学习

#### `video_progress`

- `id`
- `user_id`
- `media_item_id`
- `max_verified_position_ms`
- `verified_watch_ms`
- `completed_at`
- `last_watched_at`
- `created_at`
- `updated_at`

唯一约束：`user_id + media_item_id`。

#### `watch_sessions`

- `id`
- `user_id`
- `media_item_id`
- `plan_item_id`
- `device_id`
- `status`
- `started_position_ms`
- `last_reported_position_ms`
- `max_verified_position_ms`
- `verified_watch_ms`
- `last_sequence`
- `last_heartbeat_at`
- `alive_check_due_watch_ms`
- `alive_check_pending`
- `started_at`
- `ended_at`
- `created_at`
- `updated_at`

#### `alive_checks`

- `id`
- `watch_session_id`
- `required_at`
- `responded_at`
- `status`：PASSED / FAILED
- `created_at`

### 10.6 题目

#### `questions`

- `id`
- `media_item_id`
- `question_type`
- `content`
- `explanation`
- `enabled`
- `sort_order`
- `created_at`
- `updated_at`

#### `question_options`

- `id`
- `question_id`
- `content`
- `correct`
- `sort_order`

#### `quiz_attempts`

- `id`
- `user_id`
- `media_item_id`
- `score`
- `correct_count`
- `total_count`
- `duration_ms`
- `submitted_at`
- `created_at`

#### `quiz_answers`

- `id`
- `attempt_id`
- `question_id`
- `selected_option_id`
- `correct`
- `duration_ms`
- `created_at`

### 10.7 专注计时

#### `focus_sessions`

- `id`
- `user_id`
- `plan_item_id`
- `media_item_id`
- `focus_type`
- `status`
- `planned_seconds`
- `actual_seconds`
- `started_at`
- `paused_at`
- `ended_at`
- `created_at`
- `updated_at`

### 10.8 报表

#### `daily_reports`

- `id`
- `user_id`
- `report_date`
- `payload_json`
- `judgment_text`
- `generated_at`

唯一约束：`user_id + report_date`。

周报 V1 可实时查询，不强制落表。

### 10.9 AI、转写与摘要

#### `transcription_jobs`

- `id`
- `media_item_id`
- `status`
- `attempt_count`
- `last_error`
- `started_at`
- `finished_at`
- `created_at`
- `updated_at`

#### `transcript_segments`

- `id`
- `media_item_id`
- `segment_index`
- `start_ms`
- `end_ms`
- `text`
- `created_at`

#### `video_section_summaries`

- `id`
- `media_item_id`
- `section_index`
- `start_ms`
- `end_ms`
- `summary`
- `created_at`

#### `video_summaries`

- `id`
- `media_item_id`
- `summary`
- `outline_json`
- `model_name`
- `generated_at`

#### `ai_conversations`

- `id`
- `user_id`
- `scope`：GENERAL / VIDEO
- `media_item_id`
- `title`
- `created_at`
- `updated_at`

#### `ai_messages`

- `id`
- `conversation_id`
- `role`
- `content`
- `citations_json`
- `model_name`
- `input_tokens`
- `output_tokens`
- `created_at`

SQLite FTS5：

- 为 `transcript_segments.text` 创建虚拟全文检索表。
- 通过触发器保持主表和 FTS 表同步。
- 启动时验证 FTS5 可用；不可用时阻止 AI 视频问答进入 READY。

---

## 11. API 设计

### 11.1 通用规则

- 前缀：`/api/v1`
- 成功响应直接返回资源或 DTO，不使用多层无意义包装。
- 错误使用 RFC Problem Details。
- 所有受保护接口需要 Bearer Token。
- 访问 Token 15 分钟。
- Refresh Token 30 天，可撤销和轮换。
- 所有写接口做 Bean Validation。
- 心跳包含单调递增 `sequence`，重复序号幂等忽略。
- 所有列表接口预留分页参数。
- SSE Content-Type：`text/event-stream`。

### 11.2 主要接口

#### 认证

```text
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/me
GET  /api/v1/preferences
PUT  /api/v1/preferences
```

#### 首页与考试

```text
GET /api/v1/dashboard
GET /api/v1/exam-goal
PUT /api/v1/exam-goal
GET /api/v1/exam-progress
```

#### 课程

```text
GET /api/v1/courses
GET /api/v1/courses/{courseId}
GET /api/v1/lessons/{lessonId}
```

#### 每日计划

```text
GET    /api/v1/plans/{date}
POST   /api/v1/plans/{date}/items
PUT    /api/v1/plans/{date}/items/{itemId}
DELETE /api/v1/plans/{date}/items/{itemId}
POST   /api/v1/plans/{date}/lock
POST   /api/v1/plans/{date}/abandon
```

仅 DRAFT 允许增删改。

#### 欠债

```text
GET  /api/v1/debts
POST /api/v1/plans/{date}/debt-items
```

`debt-items` 将选定欠债加入 DRAFT 计划。

#### 播放与学习

```text
POST /api/v1/lessons/{lessonId}/watch-sessions
POST /api/v1/watch-sessions/{sessionId}/heartbeat
POST /api/v1/watch-sessions/{sessionId}/alive-check
POST /api/v1/watch-sessions/{sessionId}/stop
GET  /api/v1/playback/{ticket}/master.m3u8
GET  /api/v1/playback/{ticket}/stream
GET  /api/v1/playback/{ticket}/proxy/**
```

#### 答题

```text
GET  /api/v1/lessons/{lessonId}/quiz
POST /api/v1/lessons/{lessonId}/quiz-attempts
GET  /api/v1/lessons/{lessonId}/quiz-attempts
```

提交答题时可携带 `planItemId`。服务端必须校验该任务属于当前用户且关联同一视频；完整提交后将该任务的 `quiz_completed` 标记为真。

#### 专注

```text
POST /api/v1/focus-sessions
POST /api/v1/focus-sessions/{id}/pause
POST /api/v1/focus-sessions/{id}/resume
POST /api/v1/focus-sessions/{id}/finish
POST /api/v1/focus-sessions/{id}/cancel
GET  /api/v1/focus-sessions/active
```

#### 报表

```text
GET /api/v1/reports/daily?date=YYYY-MM-DD
GET /api/v1/reports/weekly?weekStart=YYYY-MM-DD
```

#### AI

```text
POST /api/v1/ai/conversations
GET  /api/v1/ai/conversations
GET  /api/v1/ai/conversations/{id}/messages
POST /api/v1/ai/conversations/{id}/messages:stream
GET  /api/v1/lessons/{lessonId}/ai-status
```

SSE 事件：

```text
message_start
tool_status
delta
citation
message_end
error
```

---

## 12. Emby 集成

### 12.1 配置

服务端环境变量：

```text
EMBY_BASE_URL
EMBY_API_KEY
EMBY_USER_ID
```

要求：

- 使用独立 Emby 集成密钥。
- 使用只允许访问学习媒体库的 Emby 用户。
- Flutter 永远不能拿到 Emby API Key。
- Emby 地址只允许配置一个固定可信源，防止 SSRF。

### 12.2 同步

管理员将一个 Emby Series 或 Folder 绑定到课程。

同步流程：

1. 读取父节点下视频。
2. 更新本地 `media_items` 快照。
3. 保留本地启用状态、排序和题目。
4. 新视频默认启用。
5. Emby 删除的视频在本地标记为不可用，不级联删除学习记录。
6. 定时每 15 分钟同步，也支持管理员手动同步。

本地快照用于：

- 稳定业务外键。
- 避免每个页面实时调用 Emby。
- Emby 短暂不可用时仍可查看课程和历史记录。

### 12.3 播放票据

创建观看会话时，服务端返回短期播放票据。

票据载荷：

- 用户 ID。
- 媒体项 ID。
- 观看会话 ID。
- 设备 ID。
- 过期时间。
- 随机 nonce。

票据使用服务端 HMAC 签名，不包含 Emby 密钥。

有效期默认 2 小时。

### 12.4 媒体代理

V1 媒体全部经过服务端代理，原因是并发低于 5，优先保护密钥和简化客户端。

代理必须：

- 流式转发，不将整个视频读入内存。
- 转发 `Range`、`If-Range`。
- 返回 `Content-Range`、`Accept-Ranges`、`Content-Length` 和 `Content-Type`。
- 支持 Emby HLS master playlist。
- 重写 HLS 内相对路径，所有 segment 继续通过同一票据代理。
- 限制代理目标只能是配置的 Emby。
- 会话停止时通知 Emby 清理活动转码。
- 票据过期或用户不匹配时返回 401/403。

### 12.5 播放选择

优先级：

1. Emby PlaybackInfo 返回可直放的 iOS 兼容源时，使用 direct stream。
2. 否则请求 Emby HLS，视频 H.264、音频 AAC。
3. V1 不在上岸后端自行转码视频。

---

## 13. 视频转写与摘要

### 13.1 处理流程

```text
发现或手动选择视频
  ↓
创建 transcription_job
  ↓
从 Emby 获取媒体流
  ↓
FFmpeg 抽取 16kHz 单声道音频
  ↓
按 10 分钟切片
  ↓
调用 ASR Provider
  ↓
保存带时间戳 transcript_segments
  ↓
按 5~10 分钟生成 section summary
  ↓
使用所有 section summary 生成 global summary
  ↓
构建 FTS5 索引
  ↓
READY
```

状态：

```text
PENDING
EXTRACTING_AUDIO
TRANSCRIBING
SUMMARIZING
READY
FAILED
```

规则：

- 同一媒体项最多一个活动任务。
- 失败记录错误，可由管理员重试。
- 任务重试不产生重复 segment。
- 临时音频在完成或失败后删除。
- 单任务并发 1，避免小服务器被打满。
- FFmpeg 进程设置超时和输出大小限制。

### 13.2 ASR Provider

定义接口，不把业务绑定到特定厂商：

```java
public interface TranscriptionProvider {
    TranscriptionResult transcribe(Path audioChunk, TranscriptionRequest request);
}
```

V1 提供 OpenAI-compatible HTTP 实现，通过环境变量配置：

```text
ASR_BASE_URL
ASR_API_KEY
ASR_MODEL
```

### 13.3 视频问答上下文

每次视频问答包含：

1. 全局摘要。
2. 当前时间前后 3 分钟转写。
3. FTS5 根据问题检索的前 8 个片段。
4. 与命中片段相关的 section summaries。
5. 用户问题。
6. 可选联网搜索结果。

如果完整转写估算 Token 低于模型上下文阈值，可直接加入完整转写；否则使用上述分层上下文。

视频转写和网页内容均视为不可信数据，不能覆盖系统指令。

---

## 14. AI 架构

### 14.1 原则

- Agent Runtime 在后端。
- Flutter 只负责 Chat UI、会话列表、SSE 消费和引用展示。
- 使用 LangChain4j 的 AI Services、Tool Calling 和 MCP ToolProvider。
- 不自行实现模型工具循环。
- 不使用实验性多智能体模块。
- 所有工具只读。
- 所有 MCP 工具必须显式白名单。
- 单用户同时只允许一个 AI 流式请求。

### 14.2 模型配置

```text
LLM_BASE_URL
LLM_API_KEY
LLM_MODEL
LLM_MAX_CONTEXT_TOKENS
LLM_TEMPERATURE
```

兼容 OpenAI 风格接口。

### 14.3 内部只读工具

```text
get_today_plan_summary
get_open_debt_summary
get_exam_progress
get_daily_report
get_weekly_report
search_video_transcript
get_video_summary
```

工具层不能注入 Repository 写接口。

### 14.4 MCP

配置：

```text
WEB_SEARCH_MCP_URL
WEB_SEARCH_MCP_BEARER_TOKEN
WEB_SEARCH_MCP_ALLOWED_TOOLS
```

V1 优先 Streamable HTTP。

安全：

- 只连接配置中的固定 URL。
- 只暴露白名单搜索类工具。
- 禁止 filesystem、shell、email、calendar、database write 等工具。
- 工具响应长度有上限。
- 工具超时默认 20 秒。
- 搜索失败不导致整个对话失败，AI 必须说明未获得联网结果。

### 14.5 聊天记忆

- 会话与消息持久化到 SQLite。
- 每次请求加载最近消息。
- 超过上下文阈值时，保留最近消息并使用历史摘要。
- 不把完整聊天历史无限塞入模型。
- 记录模型名和 token 使用量。
- 管理后台不展示用户聊天正文，除非将来明确增加审计功能。

---

## 15. iOS App 架构

### 15.1 目录

```text
lib/
├── app/
│   ├── app.dart
│   ├── router.dart
│   └── bootstrap.dart
├── core/
│   ├── api/
│   ├── auth/
│   ├── error/
│   ├── storage/
│   ├── theme/
│   └── widgets/
└── features/
    ├── auth/
    ├── dashboard/
    ├── exam/
    ├── catalog/
    ├── planning/
    ├── debt/
    ├── player/
    ├── quiz/
    ├── focus/
    ├── reporting/
    ├── ai_chat/
    └── profile/
```

每个 feature：

```text
feature/
├── data/
├── domain/
└── presentation/
```

保持适度，不为每个小 DTO 创建五层抽象。

### 15.2 客户端职责

客户端负责：

- 页面和交互。
- 输入校验。
- 播放器控制。
- 前后台生命周期。
- 心跳发送。
- SSE 渲染。
- Keychain Token 存储。
- 少量偏好设置。

服务端负责：

- 计划是否可编辑。
- 欠债计算。
- 视频可信进度。
- 验活触发。
- 视频是否完成。
- 题目是否解锁。
- 报表和压力计算。
- AI 上下文与工具权限。

### 15.3 本地存储

V1 不引入 Drift 或本地 SQLite。

使用：

- `flutter_secure_storage`：Access/Refresh Token。
- `shared_preferences`：主题、验活偏好、设备 ID 等非敏感设置。

原因：

- 视频和 AI 本身需要联网。
- 业务数据以服务端为准。
- 避免在 V1 提前实现复杂离线同步。

### 15.4 导航

底部五个 Tab：

```text
首页 | 学习 | AI | 数据 | 我的
```

主要页面：

- 登录。
- 首次考试目标设置。
- 首页。
- 计划编辑与锁定。
- 课程列表和详情。
- 视频播放。
- 课后答题。
- 专注计时。
- 欠债列表。
- 日报和周报。
- AI 对话。
- 设置，包括验活等级和日终时间。

### 15.5 视频页面

手机竖屏：

- 顶部视频。
- 标题和可信进度。
- 只允许回看的进度条。
- 播放、暂停、倍速。
- AI 按钮。
- AI 使用 Bottom Sheet。
- 验活使用不可绕过的模态框。

App 生命周期：

- `inactive`、`paused`、`detached` 时暂停。
- 恢复后重新向服务端确认会话状态。
- 心跳连续 3 次失败时暂停并提示网络异常。
- 服务端拒绝进度时，播放器回退到服务端可信位置。

### 15.6 UI 原则

- V1 仅亮色主题。
- 以 iOS 使用习惯为主。
- 系统字体，支持 Dynamic Type。
- 颜色不是唯一状态表达方式。
- 主要操作最小点击区域 44pt。
- 重要确认使用触觉反馈。
- 不为未来 Android 牺牲当前 iOS 体验。

---

## 16. 管理后台

使用 Spring Security Session、Thymeleaf、CSRF 和服务器渲染。

路径：

```text
/admin/login
/admin/users
/admin/emby
/admin/courses
/admin/courses/{id}/lessons
/admin/lessons/{id}/questions
/admin/transcriptions
/admin/health
```

权限：

- 仅 ADMIN。
- 管理后台与 App Token 认证分离。
- Cookie 使用 HttpOnly、Secure、SameSite=Lax。
- 登录失败有基本限速。
- 首个管理员通过环境变量引导创建，首次启动后要求修改密码。

V1 后台功能以可用为主，不做复杂设计系统。

---

## 17. 安全要求

- 生产环境 HTTPS。
- 禁止在仓库提交任何密钥。
- `.env.example` 只放变量名。
- JWT Secret 至少 32 字节随机值。
- Refresh Token 数据库存哈希，不存明文。
- 密码使用 BCrypt strength 12。
- Emby API Key、LLM Key、ASR Key、MCP Token 只在后端。
- 播放代理固定目标，禁止用户传任意 URL。
- HLS 路径重写需防目录穿越。
- 所有资源查询校验当前用户所有权。
- AI 工具白名单。
- AI 无写工具。
- Transcript、搜索结果和 MCP 返回均不能作为系统指令执行。
- 日志不记录 Token、密码、API Key 和完整媒体 URL。
- AI 日志默认不记录完整 Prompt。
- 管理后台开启 CSRF。
- App 端不信任本地时间和本地完成状态。

---

## 18. 错误处理

错误编码示例：

```text
AUTH_INVALID_CREDENTIALS
PLAN_ALREADY_LOCKED
PLAN_NOT_LOCKED
PLAN_ALREADY_CLOSED
DEBT_NOT_OPEN
VIDEO_NOT_COMPLETED
SEEK_NOT_ALLOWED
WATCH_SESSION_EXPIRED
WATCH_HEARTBEAT_REJECTED
ALIVE_CHECK_REQUIRED
QUIZ_LOCKED
EMBY_UNAVAILABLE
MEDIA_NOT_AVAILABLE
TRANSCRIPT_NOT_READY
AI_PROVIDER_UNAVAILABLE
MCP_SEARCH_UNAVAILABLE
```

客户端规则：

- 展示可操作提示，不直接显示堆栈。
- 网络错误支持重试。
- 对 `SEEK_NOT_ALLOWED`，播放器回到服务端位置。
- 对 `TRANSCRIPT_NOT_READY`，视频 AI 显示“视频内容仍在处理中”，常规问答仍可用。
- AI 流中断保留已接收内容并允许重试。

---

## 19. 可观测性与运维

必须：

- Spring Boot Actuator `/actuator/health`。
- 每个请求生成 `requestId`。
- 日志包含用户 ID、会话 ID、模块和耗时，不包含敏感内容。
- Emby、ASR、LLM、MCP 调用记录耗时和状态。
- 转写任务可见状态和最后错误。
- AI 记录 token 使用量。
- SQLite WAL 文件大小监控日志。
- 每日备份。
- 至少保留 7 天备份。

不做：

- Prometheus。
- Grafana。
- 分布式追踪。
- ELK。

---

## 20. 备份与恢复

SQLite WAL 模式下不能简单只复制主数据库文件。

备份使用：

```bash
sqlite3 /data/study.db ".backup '/backup/study-YYYYMMDD-HHMMSS.db'"
```

流程：

1. 生成在线一致性备份。
2. 校验 `PRAGMA integrity_check`。
3. 压缩。
4. 保留最近 7 份日备和 4 份周备。
5. 定期做恢复演练。

转写文本已经在数据库备份内。

恢复：

1. 停止服务。
2. 备份当前损坏文件。
3. 校验目标备份。
4. 替换数据库。
5. 启动服务。
6. 检查 Flyway 和健康接口。

---

## 21. 测试策略

### 21.1 后端

单元测试：

- 状态机。
- 欠债计算。
- 可信进度校验。
- 验活调度。
- 考试进度压力。
- 日报和审判模板。
- 视频上下文构建。
- MCP 工具白名单。

集成测试：

- 临时 SQLite 文件。
- Flyway Core 与 SQLite 数据库模块加载成功，并完成全量迁移。
- 外键和唯一约束。
- Spring Security。
- REST API。
- SSE。
- Emby 使用 WireMock。
- LLM、ASR、MCP 使用 Stub/WireMock。
- Range 转发和 HLS 重写。

契约测试：

- OpenAPI 生成成功。
- 关键 DTO 与 iOS 解析一致。
- 错误码稳定。

### 21.2 Flutter

单元测试：

- Riverpod Controller。
- Token 刷新。
- 心跳状态机。
- Seek Guard。
- SSE parser。
- 计时器。

Widget 测试：

- 首页。
- 计划锁定。
- 开摆确认。
- 欠债展示。
- 验活弹窗。
- 题目页面。
- AI 对话增量渲染。

集成测试：

- 登录。
- 创建和锁定计划。
- 启动学习会话。
- 完成题目。
- 开摆生成欠债。
- AI 普通问答。

真实视频播放需在至少一台物理 iPhone 做 smoke test。

### 21.3 完成门槛

每个任务提交前：

```bash
make format
make verify
```

必须通过：

- Java 编译和测试。
- Flutter format、analyze、test。
- OpenAPI 生成。
- 无 secret 扫描命中。
- 无禁用测试。
- 无未解释的 warning。

---

## 22. CI

建议 GitHub Actions：

### `server-verify`

- Ubuntu。
- Java 21。
- `./mvnw verify`。
- 生成 OpenAPI。
- 上传测试报告。

### `ios-verify`

- macOS。
- FVM 安装固定 Flutter。
- `flutter pub get`。
- `dart format --output=none --set-exit-if-changed .`
- `flutter analyze`。
- `flutter test`。
- 构建 iOS Simulator，禁止签名。

### `security`

- Secret scan。
- Maven 和 Dart 依赖审计。
- Dockerfile lint。

CI 不负责 App Store 签名。

---

## 23. 部署

生产推荐：

```text
Caddy
  ↓ HTTPS
Spring Boot Container
  ├─ /data/study.db
  ├─ /data/tmp
  └─ ffmpeg
       ↓
     Emby
```

关键约束：

- 数据卷必须是宿主机本地磁盘。
- 服务实例数固定为 1。
- Emby 和后端之间优先内网。
- AI/ASR/MCP 通过出站 HTTPS。
- Docker 容器限制 FFmpeg 和服务内存。
- 媒体代理不设置过小的反向代理超时。

环境变量：

```text
APP_BASE_URL
JWT_SECRET
PLAYBACK_TICKET_SECRET
ADMIN_BOOTSTRAP_USERNAME
ADMIN_BOOTSTRAP_PASSWORD
EMBY_BASE_URL
EMBY_API_KEY
EMBY_USER_ID
LLM_BASE_URL
LLM_API_KEY
LLM_MODEL
LLM_MAX_CONTEXT_TOKENS
LLM_TEMPERATURE
ASR_BASE_URL
ASR_API_KEY
ASR_MODEL
WEB_SEARCH_MCP_URL
WEB_SEARCH_MCP_BEARER_TOKEN
WEB_SEARCH_MCP_ALLOWED_TOOLS
QUIZ_DEBT_ESTIMATE_SECONDS
DATA_DIR
BACKUP_DIR
```

---

## 24. 性能目标

在同时在线不超过 5 人的前提下：

- 普通业务接口 P95 小于 300ms，不包含外部服务。
- Dashboard P95 小于 500ms。
- 心跳处理 P95 小于 100ms。
- 单用户同时仅一个观看会话。
- 单用户同时仅一个 AI 流。
- 转写并发固定 1。
- 媒体代理不得整文件缓冲。
- 后端稳定运行 7 天无锁死和数据库损坏。
- 数据库出现 `SQLITE_BUSY` 时有 5 秒等待，并记录指标日志。

---

## 25. 未来平台保留

虽然 V1 只实现 iOS，但以下设计必须平台中立：

- 所有业务规则在服务端。
- API 不出现 `/ios`。
- SSE 协议不依赖 Flutter。
- 媒体播放票据不依赖 iOS。
- 使用 `/api/v1` 版本化。
- DTO 不包含 Flutter 类型。
- Android 将复用 Flutter 工程。
- PC 使用 Web，不创建桌面客户端。
- 未来迁移 PostgreSQL 时保持应用接口不变。

不为未来平台提前创建空项目或空模块。

---

## 26. 数据库升级触发条件

继续使用 SQLite，直到出现任一条件：

- 需要运行多个后端实例。
- 并发写导致持续 `SQLITE_BUSY`。
- 同时活跃学习用户超过约 20，且写入显著增长。
- 需要高可用或无停机故障切换。
- 数据需要由多服务直接访问。
- 运维要求集中备份和时间点恢复。

迁移目标为 PostgreSQL。当前仓库通过 Repository 隔离和 Flyway 保持迁移可能性，但 V1 不编写 PostgreSQL 双兼容 SQL。

---

## 27. V1 验收场景

### 场景 A：完整学习闭环

1. 管理员绑定 Emby 剧集。
2. 同步出视频。
3. 管理员给视频配置题目。
4. 用户设置考试目标。
5. 用户把视频加入今日计划。
6. 用户锁定计划。
7. 用户播放视频。
8. 快进到未看区域被拒绝。
9. 触发并通过随机验活。
10. 视频完成。
11. 用户提交课后题。
12. 计划任务完成。
13. 日报显示学习和答题数据。

### 场景 B：开摆与欠债

1. 用户锁定包含两个任务的计划。
2. 完成第一个，第二个只做一部分。
3. 点击开摆。
4. 页面明确显示新增欠债。
5. 计划状态变为 ABANDONED。
6. 欠债记录保留原任务和剩余量。
7. 晚间审判显示开摆和欠债。
8. 第二天计划页优先展示该欠债。
9. 完成还债任务后欠债变为 PAID。

### 场景 C：AI 普通问答

1. 用户进入 AI Tab。
2. 发送常规问题。
3. AI 流式返回。
4. 用户询问今日计划或欠债。
5. AI 调用只读工具。
6. 用户询问需要最新信息的问题。
7. AI 调用 MCP 搜索并返回来源。
8. AI 不能修改任何数据。

### 场景 D：视频 AI

1. 管理员触发视频转写。
2. 任务生成时间戳转写、分段摘要和全局摘要。
3. 用户播放视频并打开 AI Bottom Sheet。
4. 用户问当前知识点。
5. AI 使用当前时间附近文本和相关片段回答。
6. 回答包含时间戳。
7. 用户问视频整体内容，AI 使用全局和分段摘要回答。
8. Transcript 未 READY 时明确提示，常规问答仍可用。

### 场景 E：恢复与备份

1. 运行在线备份。
2. 校验备份完整性。
3. 在独立目录恢复。
4. 启动服务。
5. 用户、课程、计划、进度、欠债和转写均存在。

---

## 28. Definition of Done

V1 只有同时满足以下条件才完成：

- 所有 V1 验收场景通过。
- 后端和 Flutter 测试全部通过。
- 一台物理 iPhone 连续使用 7 天无阻断性问题。
- Emby Key 未出现在 App 包、日志和 API 响应。
- 未看区间无法通过正常 UI 和直接 API 心跳绕过。
- 开摆和正常日终都会幂等生成欠债。
- AI 所有工具均只读。
- MCP 工具白名单测试通过。
- 视频问答能引用时间戳。
- 数据库可在线备份并成功恢复。
- 文档、环境变量和运行命令完整。
- 没有 Android、Web、微服务、Redis、向量库等越界实现。

---

## 29. 实施顺序

严格按以下垂直切片：

1. 仓库、CI、后端和 iOS 基础。
2. 身份认证。
3. Emby 课程同步。
4. 考试目标和首页。
5. 每日计划、锁定、开摆和欠债。
6. 播放代理。
7. 可信观看与验活。
8. 课后答题。
9. 专注计时。
10. 日报、周报和晚间审判。
11. 转写和摘要。
12. AI 普通问答、只读工具和 MCP。
13. 视频 AI。
14. 运维、备份、真机验收和 TestFlight。

每个切片必须独立测试、独立提交，不允许先铺满空壳再统一补实现。

---

## 30. 参考基线

实现时以以下官方资料为技术事实来源：

- Flutter Supported Deployment Platforms。
- Flutter iOS Deployment。
- Flutter `video_player` package。
- Emby REST API、API Key Authentication、PlaybackInfo、Dynamic HLS 和 Playstate API。
- SQLite WAL、Isolation 和 FTS5 文档。
- Spring Boot 4.1 System Requirements。
- LangChain4j Spring Boot Integration、Tools、MCP 和 Chat Memory。
- springdoc-openapi Spring Boot 4 compatibility documentation。
