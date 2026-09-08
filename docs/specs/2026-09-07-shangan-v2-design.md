# 上岸 V2 产品与技术设计规范

- 文档状态：待人工批准（批准后冻结）
- 版本线：V2.0.0
- 唯一 UI 事实来源：`docs/prototypes/shangan-v2-prototype.html`（35 个移动端屏 + 11 个管理后台页面）
- 取代文档：`docs/specs/2026-08-27-shangan-v1-design.md`（V2 上线后删除）
- 相关 ADR：ADR-0025 ~ ADR-0032

## 1. 文档定位

本文件是上岸 V2 的唯一需求与技术事实来源。V2 不是 V1 的增量迭代，而是一次**产品模型重构**：把「考试目标 → 作战单 → 可信播放 → 答题 → 日终结算 → 学习欠债 → 晚间审判」这条闭环，替换为「**Todo 中心 + 督学监督**」模型。

聊天记录不是需求来源。本文件未写明的能力不进入 V2。

## 2. 版本目标

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

### 2.1 明确的非目标

V2 **不实现**，且要从代码库中移除的 V1 能力：

| 被移除的能力 | 处置 |
|---|---|
| 可信播放（可信最大位置、播放票据、位置跳跃拒绝、按钮授权快进） | 全部删除，改为普通播放器 |
| 进度验活（alive check）与三次心跳暂停 | 全部删除 |
| 学习欠债（debt / repayment / waive） | 删除，改为「未完成汇总」纯视图 |
| 答题与题库（question / quiz / draft / 发布） | 全部删除 |
| 模拟考试与考试预置（mock exam / preset） | 全部删除 |
| 作战单整单快照与版本号（battle order / plan revision） | 删除，改为逐条增删改 |
| 日终结算、日报、周报、晚间审判 | 删除，改为「数据」Tab 的实时聚合 |
| AI 内容生产（ASR / LLM / OpenRouter / 全文 / 摘要 / 题目草稿） | 全部删除，含配置项与依赖 |
| 视觉学习伙伴「毛线团团」（companion） | 删除，V2 不含任何伴学立绘 |
| `daily_plans` / `daily_plan_items` / `daily_day_outcomes` 等计划域表 | 由 `todos` 取代 |

V2 仍然**不做**：PC 学习 Web、桌面客户端、支付、商城、社交、离线视频、DRM、微服务、Redis、Kafka、向量库、AI Chat / 智能体 / MCP。

以下四项曾只出现在高保真原型里、规范从未定义，经 ADR-0035 裁定同样**不做**，原型元素已删除：

| 越界元素 | 不做的理由 |
|---|---|
| 播放页「定时关闭」（睡眠定时） | 与 `watchedMs` 只在前台真实播放时累加的口径冲突，且不在 V2 范围（见 §10.7） |
| 课程详情「直接播放（不加入待办）」 | 无 `todoId` 则进度与时长无处上报，属数据正确性问题（见 §10.3、§10.7） |
| 新建待办「加入今日」开关 / 待办池 | `todos.local_date` 为 `NOT NULL`，无日期状态需要改已发布的 V001 baseline（见 §5.4） |
| 学员报告「主要问题 / 建议动作」与「导出」 | 自动诊断建议属 AI 内容生产入口；督学端无导出端点（见 §10.6、§14） |

清理方式不是逐包删除，而是按 ADR-0032 的**保留白名单重建代码基线**：只保留基础设施（配置、异常处理、请求日志、UUID、运行配置）、认证（JWT / 登录 / 刷新）、Emby 客户端与流代理、后台登录与健康骨架，以及 Flutter 的 API / 认证 / 存储 / 服务端地址 / 主题 / 常亮 / 10 个通用组件 / 登录页。业务层全部重建，不做平移。

### 2.2 规模与运行约束

沿用 V1：单服务进程、本机 SQLite WAL、少于 5 人同时在线、Hikari 最大连接 4、Emby 作为唯一媒体源。

## 3. 术语表

| 术语 | 定义 |
|---|---|
| Todo | 用户某一天要做的一件事，是 V2 的唯一任务实体，有三种类型 |
| 课程 Todo | `todo_type=COURSE`，关联一个学习资源，统计观看进度与观看时长 |
| 专注 Todo | `todo_type=FOCUS`，有名称与倒计时时长，统计专注时长与完成 / 未完成 |
| 待办 Todo | `todo_type=TASK`，纯文本，用户自行勾选完成 |
| 目标进度 | 课程 Todo 的完成标准，千分比表示（`300` = 30%），达到即判定完成 |
| 学习资源 | 课程下的一个可学习条目，`resource_type` 为 `VIDEO` 或 `DOCUMENT` |
| 元数据投影 | 从 Emby 拉取的流派 / 标签 / 人物只读快照，每次同步整表重写 |
| 有效操作 | 能证明用户在学习的行为：进度上报、完成、勾选、附件上传、创建 / 删除 Todo、催办回应。**心跳不是有效操作** |
| 在线 | `now - last_heartbeat_at <= presence_grace_seconds` |
| 空闲时长 | `now - last_effective_action_at` |
| 催办（Nag） | 服务端或督学人向学员投递的一次强提醒，学员必须填写原因回应 |
| 督学人 | 与学员绑定、可查看其学习情况并发起催办的账号 |
| 归档 | 实体的软下线状态，学习端不可见、不可新建引用，历史统计仍可查 |
| 彻底删除 | 只能在归档区执行的物理删除，必须走级联清理清单 |
| 补记完成 | 对历史日期未完成 Todo 的事后确认，必须填备注，统计中单列 |

## 4. 角色与权限

### 4.1 角色

| 角色 | 载体 | 能力 |
|---|---|---|
| `LEARNER` | App 学习端 | 管理自己的目标与 Todo、执行学习、上报进度、回应催办 |
| `SUPERVISOR` | App 督学端 | 只读查看绑定学员的学习情况、发起一键督学 |
| `ADMIN` | 管理后台 | 催办策略、Emby 同步、督学关系、归档与删除、用户与运行配置 |

一个账号可同时具备 `LEARNER` 与 `SUPERVISOR`。`ADMIN` 只用于后台 Session 登录，不签发 App Token。

### 4.2 督学绑定

- 每个学员**必须且只能有一个** `PRIMARY` 督学人；可另有多个 `COLLABORATOR`。
- 绑定关系只能由管理员在后台维护，App 端不可自助绑定。
- 权限为四个独立开关：`can_view`、`can_nag`、`can_edit_goal`、`can_add_todo`。V2 默认只开前两项。
- 督学端**不能**代替学员完成 Todo、上报进度或上传附件。

### 4.3 督学人快照

以下事件在写入时固化当时学员的 `PRIMARY` 督学人 ID 到 `supervisor_user_id_snapshot`，后续改绑不修改历史行：

- Todo 完成（含补记）
- Todo 删除（写入删除台账）
- 催办创建与回应

### 4.4 越权规则

- 每个用户资源接口都必须校验 `user_id` 归属。
- 督学端接口必须校验 `supervisions` 中存在未归档绑定且对应权限开关为真。
- 学员不能读取其他学员数据；督学人不能读取非绑定学员数据。

## 5. 领域模型

### 5.1 用户与身份

```text
User(id, username, passwordHash, timezone, displayName, status, archivedAt)
UserRole(userId, role)
Supervision(id, learnerUserId, supervisorUserId, kind, canView, canNag, canEditGoal, canAddTodo, archivedAt)
RefreshToken(id, userId, tokenHash, expiresAt, revokedAt)
```

- `timezone` 是 IANA 名称，决定该用户的「一天」边界与全部统计口径。
- `status` 为 `ACTIVE | ARCHIVED`；归档账号立即无法登录、不参与催办扫描与统计聚合。

### 5.2 考试目标

```text
ExamGoal(id, userId, name, examDate, note, isPrimary, createdAt, updatedAt)
```

- 目标**不绑定任何课程**，只做倒计时看板。
- 每用户最多一个 `isPrimary=true`。首页看板主行展示主目标，其余目标在同一组件内以两列紧凑格全部展示。
- 剩余天数 = `examDate - 用户时区今天`，服务端计算并返回，客户端不自行推算。
- 剩余天数着色分档：`<= 30` 为警示，`<= 60` 为提醒，其余常规。

### 5.3 课程与学习资源

```text
Course(id, externalSource, externalRef, title, overview, productionYear,
       sortOrder, status, sourceMissing, lastSyncedAt, lastSyncError, archivedAt)
LearningResource(id, courseId, resourceType, title, sortIndex,
                 durationMs, pageCount, externalRef, sourceFingerprint,
                 available, status, archivedAt)
CourseGenre(courseId, genre, sortOrder)
CourseTag(courseId, tag, sortOrder)
CoursePerson(courseId, personName, role, sortOrder)
```

- `LearningResource.id` 是**不可变业务身份**，永不重建；`externalRef`（Emby ItemId 或本地路径）是可替换的当前来源标识。
- `resourceType` 为 `VIDEO`（V2 启用）或 `DOCUMENT`（预留，见第 18 章）。
- 流派 / 标签 / 人物三张投影表是 Emby 元数据的**只读快照**，无人工编辑入口，每次同步整表重写。
- 移动端筛选维度全部由这三张表 `DISTINCT` 聚合得出，本地不维护分类或讲师主数据。
- 课程本地可编辑字段只有 `sortOrder` 与启用 / 归档状态。

### 5.4 Todo

三种类型共用一张表，类型专属字段可空。这是刻意的简化，避免三套近似结构。

```text
Todo(id, userId, localDate, todoType, title, note, sortOrder, status,
     resourceId, targetProgressPermille,
     plannedSeconds, focusState, focusStartedAt,
     requireEvidence,
     progressPositionMs, progressPage, watchedMs, focusedMs,
     completedAt, backfilled, backfillNote,
     supervisorUserIdSnapshot, createdAt, updatedAt)
TodoNoteTag(todoId, tag)
TodoAttachment(id, todoId, userId, storagePath, originalFilename,
               contentType, sizeBytes, sha256, sortOrder, createdAt)
TodoProgressEvent(id, todoId, userId, clientSeq, eventType,
                  positionMs, positionPage, deltaWatchedMs, deltaFocusedMs,
                  occurredAt, createdAt)
LessonWatchState(userId, resourceId, maxPositionMs, maxPositionPage,
                 totalWatchedMs, completedCount, lastWatchedAt)
TodoDeletion(id, userId, todoId, todoType, titleSnapshot, resourceId,
             progressSnapshotJson, reasonTag, reasonText,
             supervisorUserIdSnapshot, deletedAt)
```

字段按类型的适用性：

| 字段 | COURSE | FOCUS | TASK |
|---|---|---|---|
| `resourceId` | 必填 | — | — |
| `targetProgressPermille` | 必填，默认 `1000` | — | — |
| `progressPositionMs` / `progressPage` | 视资源类型其一 | — | — |
| `watchedMs` | 累计前台播放 | — | — |
| `plannedSeconds` / `focusState` / `focusStartedAt` | — | 必填 | — |
| `focusedMs` | — | 累计专注 | — |
| `requireEvidence` | 可选 | 可选 | 可选 |
| 附件 / 备注 / 备注标签 | 支持 | 支持 | 支持 |

- `localDate` 是用户时区下的日期字符串（`YYYY-MM-DD`），是「今日 / 历史某天」的唯一依据。
- `localDate` 在库表中为 `NOT NULL`，**V2 没有「待办池」这种无日期状态**：新建 Todo 必须落在某个 `localDate` 上（默认当前所选日期），因此新建面板不提供「加入今日」开关。想推后就用顺延（§7.5），想先记下来就建在未来某一天。
- `LessonWatchState` 是跨 Todo 的课时累计状态，用于课程详情里的「已看百分比」与续学位置；同一课时被多次加入 Todo（复看）时它不清零。
- 按 ADR-0038：同一用户、同一天、同一课时与相同目标的重复添加跳过；不同目标经确认复用原未完成项，目标取较高值、已看进度不回退。遇到历史未完成项先预览，经用户确认后顺延原项并取较高目标；不确认则跳过，不新建重复项。现存重复数据不自动清理，历史已完成项不阻止跨日复看。

### 5.5 心跳与在线

```text
UserPresence(userId, lastHeartbeatAt, lastEffectiveActionAt, appState,
             clientVersion, currentPage, activityState, activityTodoId, updatedAt)
```

- **不保存心跳明细历史**。心跳只更新 `UserPresence` 单行，避免产生大量无价值行。
- `lastEffectiveActionAt` 只被「有效操作」更新，心跳不更新它。

### 5.6 催办

```text
NagPolicy(id, scope, userId, scanIntervalMinutes, presenceGraceSeconds,
          heartbeatIntervalSeconds, firstThresholdMinutes, repeatIntervalMinutes,
          dailyMax, fullscreenTimeoutMinutes, quietStart, quietEnd,
          minPending, minReasonLength, channelFullscreenEnabled,
          channelServerchanEnabled, messageTemplate, updatedAt)
Nag(id, userId, localDate, thresholdLevel, trigger, triggeredByUserId,
    idleMinutes, pendingCount, message, status, deliveredAt, respondedAt,
    reasonTag, reasonText, supervisorUserIdSnapshot, createdAt)
NagDelivery(id, nagId, channel, status, detail, createdAt)
```

- `NagPolicy` 有一行 `scope=GLOBAL` 全局默认，可另有多行 `scope=USER` 覆盖。
- 覆盖只影响阈值与渠道类字段；`scanIntervalMinutes`、`presenceGraceSeconds`、`heartbeatIntervalSeconds` 只取全局值。
- App 端**没有**任何催办配置入口，只通过 `GET /api/v1/me` 只读拉取当前生效策略用于展示。

### 5.7 归档与审计

```text
DeletionAudit(id, entityType, entityId, entityLabel, actor, rowCountsJson, createdAt)
RuntimeSettings(单行：Emby 配置、Server 酱配置、features 开关)
```

`DeletionAudit` 只记录「删了什么实体、各表删了多少行、谁执行的」，不保存被删内容明细。

## 6. 状态机

### 6.1 Todo

```text
TODO ──首次有效进度/开始专注──> IN_PROGRESS ──达标或手动确认──> DONE
TODO ─────────────手动勾选（TASK）/补记────────────────────> DONE
```

- 没有 `ABANDONED`、没有 `CLOSED_WITH_DEBT`、没有日终自动终态。
- 未完成的 Todo 在历史日期里就保持 `TODO` 或 `IN_PROGRESS`，由「未完成汇总」聚合。
- 删除是物理删除 `todos` 行 + 写 `todo_deletions` 台账，任何状态都可删，但**必须填原因**。
- `DONE` 不可回退为未完成；写错只能删除后重建。

课程 Todo 的完成判定（服务端唯一裁决）：

```text
completed  ⟺  progressPositionMs / durationMs * 1000 >= targetProgressPermille
           ∨  用户手动点「标记完成」
```

达标后自动置 `DONE`，但不阻止继续播放与继续累计 `watchedMs`。

### 6.2 专注 Todo 的 focusState

```text
IDLE → RUNNING ⇄ PAUSED
RUNNING/PAUSED → FINISHED    （倒计时自然结束，判定完成）
RUNNING/PAUSED → STOPPED     （停止本轮，未完成；再次开始从零计时）
IDLE/RUNNING/PAUSED/STOPPED → ABANDONED （跳过，未完成，保留总时长）
STOPPED → RUNNING           （新一轮）
```

`FINISHED` 时 Todo 置 `DONE`；`ABANDONED` 时 Todo 保持未完成，`focusedMs` 已累计部分保留并进入统计。

### 6.3 催办

```text
PENDING ──投递成功──> DELIVERED ──学员填原因──> RESPONDED
PENDING/DELIVERED ──当日结束仍未回应──> EXPIRED
```

渠道降级：在线 → `FULLSCREEN`；离线，或 `FULLSCREEN` 投递后超过 `fullscreenTimeoutMinutes` 仍未 `RESPONDED` → 追加一条 `SERVERCHAN` 投递（同一个 `Nag`，不新建）。

### 6.4 实体生命周期（课程、学习资源、用户、督学关系）

```text
ACTIVE → ARCHIVED → 彻底删除（仅归档区，级联清理）
```

- 列表页只提供「归档」，不提供物理删除。
- 归档保留期默认 30 天，到期只提醒，**不自动删除**。
- 课程另有与状态正交的 `sourceMissing` 标记，表示 Emby 父节点不可达。

## 7. 客户端上报协议

### 7.1 进度上报

```http
POST /api/v1/todos/{todoId}/progress
{
  "clientSeq": 42,
  "occurredAt": "2026-09-07T09:41:12Z",
  "eventType": "PROGRESS",
  "positionMs": 1120000,
  "deltaWatchedMs": 15000,
  "appState": "FOREGROUND"
}
```

规则：

1. `clientSeq` 在单个 Todo 内单调递增。`(todoId, clientSeq)` 唯一，重复提交返回当前状态且不重复计数。
2. `positionMs` 服务端取 `max(已存, 新值)`，单调不回退；允许客户端自由拖动，不做跳跃校验。
3. `deltaWatchedMs` 只在 `appState=FOREGROUND` 且实际播放时由客户端累计；服务端直接累加。
4. 倍速不改变时长口径，`deltaWatchedMs` 按**真实经过时间**计。
5. 上报节奏：播放中每 15 秒一次，暂停 / 退出 / 完成 / 切后台时各补一次。
6. 按已接受的 ADR-0036，专注 Todo 仅通过 `/todos/{todoId}/focus/{action}` 操作接口记录服务端计算的本段时长与状态，并复用进度事件表留痕。通用进度接口仅接受课程事件，不接收 `FOCUS_*` 或非零 `deltaFocusedMs`，避免专用操作与客户端增量重复累计。
7. 任一次成功上报都会刷新 `lastEffectiveActionAt`。
8. 离线时客户端本地排队，恢复后按 `clientSeq` 顺序重放。

响应返回服务端裁决结果，客户端不自行判定完成：

```json
{ "status": "IN_PROGRESS", "positionMs": 1120000, "watchedMs": 1264000,
  "progressPermille": 442, "targetProgressPermille": 500, "completed": false }
```

### 7.2 心跳

```http
POST /api/v1/heartbeat
{ "appState": "FOREGROUND", "clientVersion": "2.0.0", "queuedEvents": 0,
  "currentPage": "PLAYER", "activityState": "VIDEO_PLAYING", "activityTodoId": "Todo UUID" }
```

```json
{ "serverTime": "2026-09-07T09:41:12Z",
  "heartbeatIntervalSeconds": 60,
  "pendingNagId": "…", "requireReason": true }
```

- 默认间隔 60 秒，实际间隔由服务端下发，客户端遵循。
- 心跳更新 `lastHeartbeatAt`、`appState` 及 ADR-0040 当前页面/活动快照，**不更新** `lastEffectiveActionAt`。
- 心跳失败不影响本地播放与计时，只影响服务端在线判定；连续失败在首页显示离线条与待同步条数。
- `pendingNagId` 非空时客户端必须拉起全屏催办弹框。

### 7.3 完成与回填

```http
POST /api/v1/todos/{todoId}/complete
{ "note": "…", "noteTags": ["NEED_REVIEW"], "backfill": false }
```

- 要求凭证（`requireEvidence=true`）时，无附件则返回稳定业务错误 `TODO_EVIDENCE_REQUIRED`。
- `backfill=true` 只允许用于历史日期，且 `note` 必填；服务端置 `backfilled=1`，统计中单列，不计入当日真实时长。

### 7.4 删除

```http
DELETE /api/v1/todos/{todoId}
{ "reasonTag": "TEMP_BUSY", "reasonText": "今天临时出差，挪到周末重排" }
```

- `reasonTag` 与 `reasonText` 都必填，`reasonText` 最少 `minReasonLength`（默认 5）个字符。
- 服务端在一个事务内：写 `todo_deletions`（含删除时进度快照与督学人快照）→ 删除附件行与磁盘文件 → 删除进度事件 → 删除 `todos` 行。
- 批量删除共用一份原因。
- 预置原因标签：`TOO_MANY_PLANNED`、`TEMP_BUSY`、`ADDED_BY_MISTAKE`、`SWITCHED_TO_OTHER`、`GAVE_UP`。

### 7.5 顺延

```http
POST /api/v1/todos/{todoId}/defer
{ "targetDate": "2026-09-08" }
```

只改 `localDate` 与 `sortOrder`，保留全部进度与附件，**不计入删除统计**。

## 8. 服务端扫描与催办

调度：单实例定时任务，周期 `scanIntervalMinutes`（默认 5 分钟）。

对每个 `status=ACTIVE` 的学员执行：

```text
localDate    = 用户时区当天
pendingCount = 当日 status != DONE 的 todos 数
idleMinutes  = now - lastEffectiveActionAt

若 pendingCount < minPending          → 跳过
若 处于 [quietStart, quietEnd) 区间    → 只累计不投递
若 idleMinutes < firstThresholdMinutes → 跳过
若 当日已投递数 >= dailyMax            → 跳过

level = 1 + floor((idleMinutes - firstThresholdMinutes) / repeatIntervalMinutes)
       （repeatIntervalMinutes = 0 时 level 恒为 1）

按 (userId, localDate, level) 幂等插入 Nag；冲突则跳过
渠道：在线 → FULLSCREEN；离线 → SERVERCHAN
```

补充规则：

- 全屏投递后超过 `fullscreenTimeoutMinutes` 仍未回应，追加 `SERVERCHAN` 投递。`fullscreenTimeoutMinutes` 随 `GET /api/v1/me` 的 `nagPolicy` 下发，App 只读展示。
- 手动催办与督学一键催办 `trigger` 分别为 `MANUAL`、`SUPERVISOR`，**不参与幂等键**，但计入 `dailyMax`。
- 删除台账驱动的督学提醒（单日删除 ≥ 3 条、原因为 `GAVE_UP`、删除进度 ≥ 50% 的课程 Todo）只通知督学人，不生成 `Nag`。
- 外部投递（Server 酱）必须设置超时，失败写 `NagDelivery.status=FAILED` 并保留可重试。

### 8.1 投递流水可观测性

每次投递尝试都写一行 `nag_deliveries`，无论成败；这是判断「催办到底有没有出去」的唯一依据。

- `nag_deliveries.status` 只有三档：`SENT` 已发出、`SHOWN` 客户端已展示、`FAILED` 投递失败。
- `nag_deliveries.detail` 是**管理员可直接阅读的中文短语**，由渠道自述失败原因，不含 SendKey、目标地址、第三方响应正文与堆栈。渠道不可用时的原因来自 `NagChannel.unavailableReason()`，Server 酱区分「Server 酱未配置 SendKey」与「Server 酱催办推送已关闭」——两者的处置动作完全不同；渠道根本没注册时写「渠道未注册」。
- `GET /admin/api/nags` 返回 `deliveryAttempts`：以 `nagId` 为键、按 `created_at` 升序的投递尝试数组，字段为 `channel` / `status` / `detail` / `createdAt`。后台催办记录页据此把「还没轮到扫描」和「两个渠道都打不通」分开显示。
- **口径说明**：`Nag` 状态机没有 `FAILED` 终态（见 6.3），因此投递全部失败的催办在 `nags.status` 上仍是 `PENDING`。后台「状态分布」卡按 `nags.status` 聚合、流水行按投递尝试判定，两处必然不等；分布卡必须在「待投递」下标注其中有多少条已尝试且全部失败，让两个数字可互相解释。不得为此新增状态机终态。
- `app.serverchan.base-url` 是 Server 酱推送基址配置项，默认即生产地址；提取为配置项只为让协议测试指向本地假服务，不是面向管理员的运行配置，因此不出现在管理后台「运行配置」页。

## 9. 统计口径

全部按用户时区聚合，实时查询，不建物化汇总表（规模允许）。

| 指标 | 定义 |
|---|---|
| 观看时长 | `sum(deltaWatchedMs)`，按 `occurredAt` 落入的本地日期归集 |
| 专注时长 | `sum(deltaFocusedMs)`，同上 |
| 完成数 / 总数 | 当日 `todos` 中 `DONE` 数 / 总数；已删除的不计入（在删除台账） |
| 完成率 | `DONE / 总数`，无 Todo 的日期不计入平均 |
| 补记完成 | 单列展示，不计入当日时长 |
| 课程 / 人物 / 流派排行 | 按 `watchedMs` 降序，人物与流派通过元数据投影表连接 |
| 专注完成率 | `focusState=FINISHED` 数 / （`FINISHED` + `ABANDONED`） |
| 热力图分档 | 无 Todo / 有 Todo 零完成（警示）/ `<1h` / `1–3h` / `>3h` |
| 备注标签聚合 | 按 `TodoNoteTag.tag` 计数，可反查 Todo |
| 删除统计 | 按 `reasonTag` 分布、按学员排行、按月趋势 |

热力图共 5 种状态、其中**时长只有 3 档**：`<1h` / `1–3h` / `>3h`，另加「无 Todo」（灰）与「有 Todo 零完成」（红底警示）。
UI 不得多画第 4 个时长色阶，图例必须把 3 个时长档全部标出，避免出现没有图例说明的颜色。

## 10. API 清单

前缀 `/api/v1`，RFC 9457 Problem Details，稳定 `errorCode`，成功响应为直接 DTO。

### 10.1 身份与个人

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` | 登录，返回 access + refresh |
| POST | `/auth/refresh` | 刷新 |
| POST | `/auth/logout` | 撤销 refresh |
| GET | `/me` | 资料、角色、督学人、生效催办策略（只读）、feature 开关 |
| PATCH | `/me/password` | 改密 |
| PATCH | `/me/timezone` | 改时区 |

### 10.2 考试目标

| 方法 | 路径 |
|---|---|
| GET | `/exam-goals` |
| POST | `/exam-goals` |
| PATCH | `/exam-goals/{id}` |
| DELETE | `/exam-goals/{id}` |

### 10.3 课程库

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/catalog/facets` | 流派 / 标签 / 人物 / 年份聚合与计数 |
| GET | `/catalog/courses` | 支持 `genre`、`tag`、`person`、`year`、`q`、`groupBy=GENRE\|PERSON` |
| GET | `/catalog/courses/{id}` | 课程详情 + 资源列表 + 本人进度 |

课程库与课程详情都是只读入口：课时行的唯一写操作是「加入今日待办」（`POST /todos`），不存在从课程库直接播放的路径（见 §10.7）。

### 10.4 Todo

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/todos` | `view=DAY\|WEEK\|MONTH`，配 `date` / `weekStart` / `month`；周月只返聚合摘要 |
| GET | `/todos/pending-summary` | 跨日期未完成汇总，按逾期天数分组 |
| POST | `/todos` | 批量创建，一次可提交多个课时 |
| PATCH | `/todos/{id}` | 标题、备注、目标进度、排序、计划时长 |
| POST | `/todos/{id}/progress` | 进度上报（幂等） |
| POST | `/todos/{id}/complete` | 完成 / 补记完成 |
| POST | `/todos/{id}/defer` | 顺延到指定日期 |
| DELETE | `/todos/{id}` | 删除（必填原因） |
| POST | `/todos/batch-defer` | 批量顺延 |
| POST | `/todos/batch-delete` | 批量删除（共用原因） |
| POST | `/todos/{id}/attachments` | 上传附件（≤10MB，单 Todo ≤9 个） |
| GET | `/todos/{id}/attachments` | 读取附件列表；响应不含 `storagePath` 与 `sha256` |
| GET | `/todos/{id}/attachments/{attachmentId}/content` | 下载附件字节流；两层归属校验 |
| DELETE | `/todos/{id}/attachments/{attachmentId}` | 删除附件 |

### 10.5 统计、心跳、催办

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stats` | `range=DAY\|WEEK\|MONTH` + 基准日期 |
| POST | `/heartbeat` | 心跳 |
| GET | `/nags/pending` | 拉取待回应催办 |
| POST | `/nags/{id}/respond` | 回应（必填原因） |

### 10.6 督学端

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/supervisor/learners` | 学员总览：在线、今日完成、异常标记 |
| GET | `/supervisor/learners/{id}` | 学员详情，`range=DAY\|WEEK\|MONTH`，含今日 Todo 只读镜像与删除记录 |
| POST | `/supervisor/learners/{id}/nag` | 一键督学 |
| GET | `/supervisor/feed` | 时间线：我发的 / 自动催办 / 删除通知 |
| GET | `/supervisor/report` | 周 / 月学员对比 |

- 督学端**没有导出端点**：CSV 导出只属于管理后台（`/admin/api/**`，见 §13 的删除流水导出）。督学端只在 App 内查看与发起催办。
- `/supervisor/report` 只返回事实指标（时长、完成率、删除次数、回应率、课程分布），不返回问题诊断或行动建议文本。

### 10.7 播放

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/playback/{resourceId}/stream` | 服务端代理 Emby 流，透传 Range |

- Emby 目标主机由配置固定，代理必须拒绝任意主机与路径穿越。
- Emby API Key 不出服务端。
- V2 取消播放票据 HMAC 机制，改为普通 Bearer 鉴权 + 归属校验。
- **播放必须挂在 Todo 上**：进度与时长只能通过 `POST /todos/{id}/progress` 上报，`(todoId, clientSeq)` 是唯一幂等键。因此课程详情页、课程库与督学端都不提供「不加入 Todo 直接播放」的入口；课时行的操作是「加入今日待办」，播放从今日 Todo 进入。没有 `todoId` 的播放会让 `positionMs` 与 `watchedMs` 无处落库，属于数据正确性问题，不是交互偏好问题。
- 播放页控制项只有：拖动进度条、快退 / 快进 10 秒、倍速 0.75–2.0、全屏。**不提供睡眠定时关闭（定时关闭胶囊）**：它会在无人观看时继续或停止累计时长，与「`watchedMs` 只在前台真实播放时累加」的口径冲突，也不在 V2 范围内。

管理后台为 Vue 3 SPA，路径前缀 `/admin`，通过内部 REST 接口 `/admin/api/**` 取数（见 ADR-0033）。
该组接口只服务后台自身，只接受管理员 Session Cookie + CSRF，不下发给 App，也不纳入 `/api/v1` 契约。

## 11. 数据库设计

### 11.1 迁移策略

V2 **重建 baseline**：删除 `V001` ~ `V029` 全部迁移与其对应的表，新建单一 `V001__shangan_v2_baseline.sql`。理由与授权见 ADR-0031（用户明确批准数据库完全重构，无需保留 V1 数据）。发布后本 baseline 即进入 append-only 约束。

### 11.2 表清单

| 域 | 表 |
|---|---|
| identity | `users`、`user_roles`、`refresh_tokens`、`supervisions` |
| goal | `exam_goals` |
| catalog | `courses`、`learning_resources`、`course_genres`、`course_tags`、`course_people`、`resource_source_mappings` |
| todo | `todos`、`todo_note_tags`、`todo_attachments`、`todo_progress_events`、`lesson_watch_states`、`todo_deletions` |
| presence | `user_presence` |
| nag | `nag_policies`、`nags`、`nag_deliveries` |
| ops | `deletion_audits`、`runtime_settings`、`SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES` |

### 11.3 关键约束

- 所有 ID 为 UUID 字符串；所有时间戳为 UTC Epoch 毫秒；`localDate` 为 `TEXT` 的 `YYYY-MM-DD`。
- `users.username` 唯一。
- `supervisions`：`UNIQUE(learner_user_id, supervisor_user_id)`；`PRIMARY` 唯一性用部分唯一索引 `WHERE kind='PRIMARY' AND archived_at IS NULL`。
- `exam_goals`：主目标唯一性用部分唯一索引 `WHERE is_primary=1`。
- `courses`：`UNIQUE(external_source, external_ref)`。
- `learning_resources`：`UNIQUE(external_ref)`；`UNIQUE(course_id, source_fingerprint)`；`FK course_id → courses ON DELETE RESTRICT`。
- `todos`：`FK user_id → users ON DELETE CASCADE`，`FK resource_id → learning_resources ON DELETE RESTRICT`；索引 `(user_id, local_date, sort_order)`、`(user_id, status)`。
- `todo_progress_events`：`UNIQUE(todo_id, client_seq)`；索引 `(user_id, occurred_at)`。
- `todo_attachments`：`storage_path` 唯一，`size_bytes BETWEEN 1 AND 10485760`。
- `lesson_watch_states`：`PK(user_id, resource_id)`。
- `nags`：部分唯一索引 `UNIQUE(user_id, local_date, threshold_level) WHERE trigger='AUTO'`。
- `nag_policies`：`scope='GLOBAL'` 行唯一；`scope='USER'` 时 `user_id` 唯一。
- PRAGMA 沿用 V1：WAL、`foreign_keys=ON`、`busy_timeout=5000`、`synchronous=NORMAL`。
- 写操作必须使用短事务。

### 11.4 级联删除顺序

彻底删除必须按依赖顺序在**单个事务**内执行，并同步清理磁盘附件文件。

课程：

```text
todo_attachments(+文件) → todo_progress_events → todo_deletions(引用该课程)
→ todos → lesson_watch_states → course_genres/tags/people
→ resource_source_mappings → learning_resources → courses → deletion_audits(+1)
```

用户：

```text
todo_attachments(+目录) → todo_progress_events → todos → todo_deletions
→ lesson_watch_states → exam_goals → nag_deliveries → nags
→ nag_policies(scope=USER) → supervisions(双向) → user_presence
→ user_bark_settings → refresh_tokens → user_roles → users → deletion_audits(+1)
```

### 11.5 孤儿数据自检

每日备份后自动执行，结果写运行日志，后台归档区可手动触发：

- 无主 `todos`、`todo_progress_events`、`todo_attachments`
- 磁盘上无对应数据库行的附件文件
- 指向已删资源的 `lesson_watch_states`
- 指向已删用户的 `nags` / `nag_deliveries` / `supervisions`

## 12. Emby 同步与迁移

### 12.1 元数据映射

| 内部字段 | Emby 来源 |
|---|---|
| `courses.title` | `Name` |
| `courses.overview` | `Overview` |
| `courses.productionYear` | `ProductionYear` |
| `course_genres.genre` | `Genres[]` |
| `course_tags.tag` | `Tags[]`（含 `TagItems[].Name`） |
| `course_people.personName / role` | `People[].Name / Type` |
| `learning_resources.title` | `Name` |
| `learning_resources.sortIndex` | `IndexNumber`（必须在 `Fields` 中显式声明）；缺失时才回退到远端列表位置，且排在课程内已知 `IndexNumber` 最大值之后。**禁止用列表位置直接推导序号**，详见 ADR-0034 |
| `learning_resources.durationMs` | `RunTimeTicks / 10000` |
| `learning_resources.sourceFingerprint` | `SHA-256(版本前缀 + Path)`，仅内存计算 |

Emby `Path` 禁止进入数据库、页面、日志与错误响应。

### 12.2 同步流程

1. 读取配置的媒体库 / 父节点，使用 `EMBY_USER_ID` 与用户作用域 API 分页递归读取 `Movie`、`Episode`、`Video`。请求的 `Fields` 必须包含 `RunTimeTicks`、`Path`、`SortName` 与 `IndexNumber`。
2. 任一页失败即整体放弃，不写部分快照，保留上一次可用快照并记录 `lastSyncError`。
3. 全部分页收齐后一次性定稿 `sortIndex`（按 12.1 规则），再计算课时映射方案（见 12.3）。
4. 在一个短事务内：更新课程基本信息 → 整表重写三张元数据投影 → 原位更新已匹配资源的 `externalRef` → 创建新资源 → 标记消失资源 `available=0` → 写映射审计。
5. 原位更新会用远端值整列覆盖 `sortIndex`，因此历史上被写错的序号在下一次同步自动纠正；纠正不重建 `LearningResource.id`，学习进度与 Todo 引用不受影响。

### 12.3 课时映射优先级

```text
1. 当前 externalRef 命中
2. 课程内唯一 sourceFingerprint 命中
3. 课程内唯一标题且时长差 ≤ 2 秒
4. 管理员逐项确认
```

一对多、多对一或其他歧义**不得自动合并**，进入「待人工确认」列表。

### 12.4 变更处置矩阵

| 变更 | 处置 | 学习记录 |
|---|---|---|
| 元数据改名 / 重打标签 / 增删人物 | 只重写投影与展示字段 | 完全不受影响 |
| 课时 `ItemId` 变化 | 原位改写 `externalRef`，本地 ID 不变 | 完全跟随 |
| 课时在 Emby 被删除 | `available=0`，行保留，`sortIndex` 冻结在最后一次同步值 | 保留；引用它的 Todo 显示「已下架」，只能补记完成或删除 |
| 课程父节点 404 / 无权限 | `sourceMissing=1`，学习端隐藏 | 保留；统计仍可查 |
| 课程换到新父节点 | 走「重新绑定向导」 | 按 12.3 原位迁移，`LearningResource.id` 不变 |
| 远端结果为空且父节点有效 | 才允许把全部资源标记不可用 | 保留 |

课时下架后学习端会看到不连续的序号（如 1、2、3、4、6、7、8）。这是预期行为：编号稳定优先于编号连续，用户按「第几讲」记住的内容不得因远端增删而平移。列表按 `sortIndex` 升序展示，不显示缺口占位。

### 12.5 重新绑定向导

读取新父节点完整远端快照 → 按 12.3 生成匹配方案 → 展示「原位 / 新增 / 下架」三类结果 → 管理员确认歧义 → 单事务提交（更新父绑定、原位改 `externalRef`、创建新资源、标记下架、写审计）。任一步失败整体回滚。

## 13. 管理后台功能清单

| 页面 | 内容 |
|---|---|
| 概览 | 在线用户、今日全局完成率与时长、待处理催办、依赖健康（Emby / Server 酱 / SQLite / 备份 / 扫描器）、近 14 天时长 |
| 在线与进度 | 心跳与有效操作明细、今日 Todo 展开、手动催办 |
| 催办策略 | 全局默认 + 按用户覆盖、渠道开关、文案模板、免打扰、原因最少字数 |
| 催办与删除 | 催办记录（触发条件、渠道、投递、回应、时延）与删除台账（原因分布、按学员、流水、CSV 导出、督学提醒规则） |
| 课程库 | 课程列表（流派 / 人物 / 标签 / 年份只读列）、Emby 绑定、同步、归档、资源清单入口 |
| Emby 同步 | 元数据变更预览、映射待确认、失联处置、重新绑定向导 |
| 督学关系 | 绑定维护、四项权限开关、事件督学人快照查看 |
| 用户 | 新增、改密、时区、归档；显示累计学习与附件占用 |
| 归档区 | 课程 / 资源 / 用户 / 督学关系四类归档对象；彻底删除预检清单、名称二次确认、孤儿自检、保留期设置 |
| 运行配置 | Emby（Base URL / API Key / User ID / 超时）、Server 酱（SendKey / 超时 / 用途开关）、feature 开关 |

管理后台使用 HttpOnly Session Cookie + CSRF；不提供任何 AI、题库、模拟考试或欠债入口。

概览页依赖健康行为三态而非布尔：Emby 是可选依赖，「未配置」是中性状态，「已配置但探测失败」才是故障。Emby 行同时展示单次 `System/Info` 探测耗时（`embyLatencyMs`），格式为「可用 · 128ms」；未配置时探测不发请求、该值恒为 0，页面必须只显示「未配置」而不是「0ms」。快照刻意不含数据库文件绝对路径，页面也不渲染任何服务器文件系统路径。

## 14. 督学端功能清单

| 屏 | 内容 |
|---|---|
| 角色切换 | 登录时选择学员端 / 督学端；会话期间固定身份，退出后重新登录才能更换 |
| 学员总览 | 在线状态、今日完成比、当前在学条目、主目标名与剩余天数、异常徽标（零完成 / 频繁删除 / 催办未回应） |
| 学员详情 | 今日 Todo 只读镜像（含目标进度）、时长、备注、今日删除记录与原因；可切周 / 月 |
| 一键督学 | 话术模板 + 自定义文本 + 渠道（自动 / 全屏 / Server 酱）+ 是否要求填写原因 |
| 提醒记录 | 我发的 / 自动催办 / 删除通知三类混排时间线，含学员回应与时延 |
| 学员报告 | 周 / 月对比：时长、完成率、删除次数、回应率；课程分布 |

督学端只读学员数据 + 发起催办；不能完成 Todo、不能上报进度、不能上传附件。V2 默认不开放代改目标与代加 Todo。

学员总览的主目标三个字段（`primaryGoalName` / `primaryGoalDaysRemaining` / `primaryGoalUrgency`）同生共死：学员没有任何考试目标时全为 `null`，客户端隐藏该行而不是渲染「剩 0 天」。剩余天数按**学员**时区由服务端计算（复用 5.2 的口径），督学端不自行推算，避免督学与学员时区不同导致两端数字对不上。

学员报告只呈现服务端聚合出的**事实指标**，不生成「主要问题」「建议动作」这类诊断或行动建议：规则式结论会被读成系统判断，也是 AI 内容生产的入口，属于 §2.1 非目标。需要解读时由督学人自己在一键督学的自定义文本里写。
督学端也没有导出入口：CSV 导出只在管理后台（§13 删除流水）。

## 15. 安全

- 禁止提交：JWT Secret、Emby API Key、Server 酱 SendKey、管理员密码、生产地址凭据。
- 生产使用 HTTPS；Refresh Token 哈希存储；密码 BCrypt strength 12。
- API 使用 Bearer Token；后台使用 Session + CSRF。
- 日志脱敏 `Authorization`、Cookie、API Key、媒体凭据、Emby 路径。
- 错误响应不含堆栈。
- 附件存储在 `DATA_DIR/attachments/{userId}/`，文件名由服务端生成，禁止使用客户端原始路径；下载需归属校验。
- 所有外部调用设置超时。
- 生产镜像非 root 运行。

## 16. 非功能要求

- 注入 `java.time.Clock`；领域与应用代码禁止直接调用 `Instant.now()`、`LocalDate.now()`、`System.currentTimeMillis()`。
- 用户「一天」边界按其 IANA 时区计算。
- 每日在线 `.backup` + `PRAGMA integrity_check`，保留 7 日 + 4 周。
- Actuator 健康端点；日志带 Request ID。
- 心跳与进度接口必须幂等且低开销（单行 upsert / 唯一键冲突忽略）。
- iOS 16+ / Android API 24+；Flutter 3.44.x + Dart 3.12.x（FVM 锁定）；Java 21 + Spring Boot 4.1.x。
- UI：亮色主题、系统字体与 Dynamic Type、最小点击区 44pt、重要状态不只靠颜色表达、全屏催办不可点击外部关闭。

## 17. 验收场景

| 编号 | 场景 | 期望 |
|---|---|---|
| A1 | 新建 3 个考试目标 | 首页目标看板一屏展示全部，主目标大号，临期目标着色 |
| A2 | 添加 2 个课时 Todo，目标进度 50% | 首页显示「目标 50%」与达标差额 |
| A3 | 播放到 51% | 服务端自动置 `DONE`，可继续播放并累计时长 |
| A4 | 播放中拖动、快进、2.0 倍速 | 全部允许；`watchedMs` 按真实经过时间累计 |
| A5 | 断网播放 3 分钟后恢复 | 队列按 `clientSeq` 重放，时长不重复计入 |
| A6 | 专注 25 分钟自然结束 | `FINISHED` + Todo `DONE`；要求拍照时未上传则不能完成 |
| A7 | 专注中途跳过 | `ABANDONED`，Todo 未完成，已专注时长仍进统计 |
| A8 | 待办勾选完成且要求凭证 | 无附件时返回 `TODO_EVIDENCE_REQUIRED` |
| A9 | 删除任意 Todo | 必须填原因；台账含进度快照与督学人快照；督学端收到删除通知 |
| A10 | 顺延历史未完成 Todo | 进度与附件保留，不计入删除统计 |
| A11 | 补记历史未完成 Todo | 必填备注，统计单列，不计入当日时长 |
| A12 | 切换日 / 周 / 月视图 | 周月返回聚合摘要，展开某天拉明细 |
| A13 | 停止操作超过阈值 | 服务端生成 `Nag`；在线时全屏弹框且必须填原因；离线走 Server 酱 |
| A14 | 同一天同一档重复扫描 | 幂等，不重复投递 |
| A15 | 免打扰时段内触发 | 不投递 |
| A16 | 督学人一键督学 | 学员收到催办；记录 `trigger=SUPERVISOR` 与操作人 |
| A17 | Emby 改课程流派与人物 | 投影整表重写；历史统计与进度不变 |
| A18 | Emby 课时 ItemId 变化 | 原位改写，进度与 Todo 全部跟随 |
| A19 | Emby 删除课时 | `available=0`；引用它的 Todo 显示已下架，仅可补记或删除 |
| A20 | Emby 父节点 404 | `sourceMissing=1`，学习端隐藏，统计保留，可走重新绑定 |
| A21 | 重新绑定到新父节点 | 单事务完成；`LearningResource.id` 不变 |
| A22 | 归档课程 | 学习端不可见且不能新建引用；历史统计仍可查 |
| A23 | 归档区彻底删除课程 | 按顺序级联；孤儿自检为 0；写审计 |
| A24 | 归档区彻底删除用户 | 附件目录清空；督学绑定解除；其他学员历史保留督学人快照 |
| A25 | 备份与恢复 | 恢复后可正常登录、看到全部 Todo 与统计 |
| A26 | 物理设备真机验证 | iPhone / iPad / Android 播放、全屏、倍速、全屏催办均正常 |

## 18. 材料（DOCUMENT）预留

V2 只启用 `VIDEO`。`DOCUMENT` 的模型与列一并落地，但由服务端开关 `features.document_resources`（默认关闭）控制可见性。材料来源确定为 **Emby 书籍媒体库**（`CollectionType=books`，Item 类型 `Book`），客户端**全量下载 PDF 后本地阅读**。

| 维度 | VIDEO | DOCUMENT |
|---|---|---|
| 本地身份 | `learning_resources.id` | 同 |
| Emby 库类型 | 视频库（`Movie` / `Episode` / `Video`） | 书籍库（`Book`） |
| 外部标识 | Emby ItemId | 同 |
| 来源指纹 | `SHA-256(版本前缀 + Path)` | 同 |
| 进度位置 | `positionMs` | `positionPage` |
| 总量 | `durationMs`（`RunTimeTicks`） | `pageCount`（Emby 不提供，客户端首次解析后回报） |
| 时长 | `watchedMs` | 复用 `watchedMs`，解释为阅读时长 |
| 完成标准 | 目标百分比 | 目标百分比（按页数） |
| 客户端 | 流式播放 | 全量下载后阅读，可离线 |
| Todo 类型 | `COURSE` | `COURSE`（不新增类型） |

元数据来源只有 Emby，不做多态适配层。视频与书籍共用同一个内部 Bean，字段映射一致（`People` 在书籍上承载作者）：

```text
ResourceMetadata { title, sortIndex, genres[], tags[], people[], year,
                   overview, durationMs?, pageCount?, sourceFingerprint }
```

因此第 12 章的同步流程、映射优先级与失联迁移规则对书籍完全适用，不新增分支。

后续版本需要补的三件事（V2 **不实现**）：

| 项 | 说明 |
|---|---|
| 页数回报 | `POST /api/v1/resources/{id}/page-count`，仅在 `pageCount IS NULL` 时写入；已有值时校验一致，不一致记警告并保留原值；管理员可重置。`pageCount` 为空的资源不能加入 Todo |
| 文件下载代理 | `GET /api/v1/resources/{id}/file`，透传 Emby 下载端点并支持 `Range`；单文件上限为配置项（默认 200MB）；Emby API Key 不出服务端，拒绝任意主机与路径穿越 |
| 客户端阅读页 | 全量下载到应用私有缓存后阅读，「我的」提供缓存清理；阅读进度按最远页数单调上报 |

V2 只需保证 `learning_resources` 含 `resource_type`、`page_count`、`position_page` 列，`ResourceMetadata` Bean 存在，以及 feature 开关生效。不引入任何 PDF 依赖。

## 19. 测试要求

服务端：

- JUnit 5 + AssertJ + MockMvc；WireMock 覆盖 Emby 与 Server 酱。
- 自动化测试不启动 SQLite / Flyway，不测试具体 SQL、数据库约束、事务与迁移。
- 必须覆盖：进度上报幂等与 `positionMs` 单调、目标进度达标判定、专注两种终态、删除原因校验与台账写入、催办扫描的阈值 / 分档 / 幂等 / 免打扰 / 渠道降级、督学权限校验、Emby 映射优先级与歧义拒绝、级联删除顺序生成、统计聚合口径。
- 涉及 Bean 构造、配置绑定、迁移或启动配置的改动，必须额外执行真实 ApplicationContext 启动 Smoke Test 并确认健康端点 `UP`。

Flutter：

- Controller 与解析器单元测试；关键确认流程（删除原因、凭证必填、全屏催办）Widget 测试；播放器使用 fake adapter。
- 真机播放必须在物理 iPhone、iPad、Android 上验证。

本地只运行本次新增或直接修改对应的窄测试；全量 `make verify` 由 GitHub CI 执行。

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


### 2026-09-08 课程进度显示修正

课程列表进度条只填充实际进度；目标为 100% 时轨道终点即目标，不重复绘制末端竖线，中间目标仍显示刻度。剩余达标时长按目标毫秒减最远播放位置计算，不能由已取整千分比反推；短时观看保留秒数。按用户要求不追加自动验证，由用户检查界面。


### 2026-09-08 统一进度轨道可见性

所有进度组件必须先显示清晰完整的背景轨道，低进度不能看起来像孤立的点。浅色背景统一采用 progressTrack / progressOutline，列表轨道为 7px；深色视频轨道提高白色背景不透明度。填充仍按真实比例，零进度不绘制提示光点，不人为放大低值。专注圆环沿用本轮剩余时间口径。按用户要求，仅更新与热重载，界面由用户验证。


## 2026-09-08 已批准：媒体库课程发现与批量导入

按 ADR-0037 补齐第 12、13 章：保存媒体库后点击「导入课程」打开弹窗，在弹窗内展示绑定范围内全部 Series / Movie 候选；页面不内嵌候选列表，弹窗独立滚动并固定关闭按钮；支持名称搜索、封面、按需课时数、分页多选与全选未导入课程。逐门串行导入并反馈结果，单门完整快照在远端读取成功后才在短事务内落库。重复导入幂等；归档来源不可重复创建或自动恢复。书籍库不参与，后续同步只更新已导入课程。


### 2026-09-08 播放与退出上报缺陷修复（T08 / T25）

静态媒体流只以 Emby Item ID 定位，不推测 MediaSourceId；保持 Range 流式透传。播放器退出用 PAUSE 补报；服务端将旧 EXIT 排队请求兼容为 PAUSE，未知事件在写库前返回 TODO_PROGRESS_EVENT_INVALID，避免触发数据库 CHECK 约束。无数据库迁移。回归验证媒体参数、206 与 Range 透传、退出事件兼容及非法事件不写入。

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

## 已批准变更：手动催办标题与课程筛选面板

见 [ADR-0041](../adr/0041-manual-nag-content-and-course-filter-panel.md)。定义手动催办独立标题与附加说明、课程库和添加课程共用三类筛选面板、防抖与固定头部，以及首页/数据页固定范围。新增协议与筛选交互已获批准；保持自动催办原行为。

## ADR-0041 / ADR-0042 增量范围（已批准）

手动催办支持可选标题（80 字）和附加说明（1000 字），自动内容不变。课程库及今日选课复用三类多选筛选面板（同类取并集、跨类取交集），应用提交、取消保留、搜索 300ms 防抖和一键清空；课程头部、首页待办前汇总与数据页周期选择固定。

个人 Bark 由当前用户在 App 设置独立维护，启用后替代该用户 Server 酱，失败不双发；系统 Bark 由后台单独配置，仅用于 Emby 同步、备份及完整性检查异常。两套目的地不互相兜底。默认分组“上岸”、critical 重要通知、shangan://home 打开 App。系统持续异常合并通知，恢复后再次异常可通知。追加 V005/V006 迁移。

## 手动 Bark 通知级别（2026-09-09 已按用户要求调整）

手动催办（后台 MANUAL 与督学 SUPERVISOR）实际通过个人 Bark 投递时，读取收件用户当前生效策略（用户覆盖优先）及其时区下的当前时间。免打扰时段内使用普通通知 level=active，时段外保留 critical；分组、点击链接、标题、正文不变。免打扰开始包含、结束不包含，支持跨午夜；起止相同表示不启用免打扰。自动催办的免打扰不投递规则、系统异常及系统测试通知不变。
