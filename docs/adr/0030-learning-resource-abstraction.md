# ADR-0030：统一「学习资源」抽象，材料以 Emby 书籍为来源（V2 预留）

- 状态：提议（待人工批准）
- 日期：2026-09-07
- 相关：配合 ADR-0027；V2 只启用 `VIDEO`

## 背景

V1 的 `media_items` 是「Emby 视频课时」的直接投影，名称与字段都假设了视频。用户提出后续要接入材料管理：把视频换成材料，元数据要与 Emby 保持一致；进一步明确了实现方向——**直接使用 Emby 的书籍媒体库做资料加载与元数据映射，客户端全量下载 PDF 后阅读**。两者的业务差别只是一个播放视频、一个看 PDF。

这是明确的未来需求，但不属于 V2 实施范围。需要现在就把抽象与来源定下来，避免届时重构课程、Todo、进度与统计模型。

## 决策

### 统一资源实体

把 `media_items` 重命名并抽象为 `learning_resources`，用 `resource_type` 区分：

| 维度 | `VIDEO`（V2 启用） | `DOCUMENT`（预留） |
|---|---|---|
| 本地身份 | `learning_resources.id`（UUID，永不重建） | 同 |
| Emby 媒体库类型 | 视频库（`Movie` / `Episode` / `Video`） | 书籍库（`CollectionType=books`，Item 类型 `Book`） |
| 外部标识 | `external_ref` = Emby ItemId | 同 |
| 来源指纹 | `SHA-256(版本前缀 + Path)` | 同 |
| 进度位置 | `position_ms` | `position_page` |
| 总量 | `duration_ms`（来自 `RunTimeTicks`） | `page_count`（Emby 不提供，见下） |
| 时长口径 | `watched_ms` | 复用 `watched_ms`，按类型解释为阅读时长 |
| 完成标准 | 目标百分比（千分比） | 目标百分比（按页数） |
| 客户端 | `video_player` 流式播放 | 全量下载后本地 PDF 阅读 |
| Todo 类型 | `COURSE` | `COURSE`（**不新增 Todo 类型**） |

关键判断：材料与视频在业务上是同一件事——「一个可学习条目，有进度、有时长、有完成标准」。因此不新增 Todo 类型、不新增统计维度、不复制课程结构，只在渲染层分叉。

`watched_ms` 一列承担观看与阅读两种语义，因为两者单位相同（前台停留毫秒），统计层按 `resource_type` 分列展示即可。`position_ms` 与 `position_page` 单位不同，必须分列。

### 元数据来源：只有 Emby

早期草案设计了 `MetadataSource` 接口 + 三个实现（Emby API / 本地 NFO / 文件路径推断）。用户明确材料直接用 Emby 书籍库后，这个抽象**被取消**：来源只有一个，多态接口是过度设计。

保留的是内部统一 Bean，视频与书籍共用：

```text
ResourceMetadata { title, sortIndex, genres[], tags[], people[], year,
                   overview, durationMs?, pageCount?, sourceFingerprint }
```

书籍的字段映射与视频完全一致（`Name` / `Genres` / `Tags` / `People` / `ProductionYear` / `Overview`），其中 `People` 承载作者。因此同步代码、映射优先级、失联迁移规则（Spec 第 12 章）**完全复用**，不为书籍新增分支。

### 页数的来源

Emby 对 `Book` 类型不返回页数，`page_count` 无法从元数据取得。定为：

- 同步时 `page_count` 为空。
- 客户端首次下载并解析 PDF 后回报一次：`POST /api/v1/resources/{id}/page-count`。
- 该接口幂等：只允许在 `page_count IS NULL` 时写入；已有值时校验一致，不一致记警告并保留原值（避免不同客户端解析差异导致进度百分比漂移）。管理员可在后台重置。
- `page_count` 为空时，该资源不能被加入 Todo（无法计算目标进度），学习端显示「待客户端首次打开后可用」。

### 文件获取与阅读方式

- 服务端新增代理下载接口 `GET /api/v1/resources/{id}/file`，透传 Emby 下载端点，支持 `Range` 以便断点续传；Emby API Key 不出服务端；目标主机固定，拒绝任意主机与路径穿越。
- 单文件大小上限为配置项（默认 200MB），超限拒绝并在后台标注。
- 客户端**全量下载到本地缓存后阅读**，不做流式分页渲染。缓存位于应用私有目录，可在「我的」中清理；下载完成后离线可读。
- 阅读进度按最远页数上报，与视频的 `positionMs` 同为单调不回退。

### V2 的实施边界

V2 **只做**以下三件与材料相关的事：

1. 表结构使用 `learning_resources` + `resource_type` + `page_count` / `position_page` 列。
2. `ResourceMetadata` Bean 视频与书籍共用（V2 只填视频字段）。
3. 服务端开关 `features.document_resources`，默认关闭；关闭时 `DOCUMENT` 资源不出现在任何学习端接口。

V2 **不做**：PDF 阅读页、书籍库同步、下载代理接口、页数回报接口、缓存管理。这些属于后续版本，需要单独的 Spec 与 Plan。V2 不引入任何 PDF 相关依赖。

## 后果

正面：

- 后续接入材料时，课程、Todo、目标进度、统计、督学视图、归档与级联删除全部不需要改动。
- 元数据来源单一，同步与迁移逻辑一份代码覆盖视频与书籍。
- 全量下载让阅读体验稳定且可离线，避免流式分页在移动端的复杂度。

负面与代价：

- 表里存在 V2 用不到的列（`page_count`、`position_page`）与枚举值（`DOCUMENT`）。这是有意预留，授权来源是用户明确要求。
- `page_count` 依赖客户端回报，引入了「服务端数据由客户端决定」的例外路径。用「只允许首次写入 + 不一致告警 + 管理员可重置」把风险限制住。
- 大文件全量下载对移动端存储与首次等待时间有要求，需要大小上限与缓存清理入口。
- `watched_ms` 一列两义，统计层必须按类型分列，否则口径会混。

## 替代方案

1. **等到真正接入材料时再抽象**。否决：届时需要重构课程、Todo、进度、统计与级联删除，改动面远大于现在预留两列。
2. **材料作为第四种 Todo 类型**。否决：业务语义与课程完全一致，拆类型会导致统计与督学视图重复实现。
3. **保留 `MetadataSource` 三实现（NFO / 文件路径）**。否决：来源已确定只有 Emby，多态接口无第二个实现者，属过度设计。
4. **流式分页渲染 PDF**。否决：用户明确要求全量加载后阅读；流式方案需要服务端分页切片或客户端范围请求管理，复杂度不匹配收益。
5. **服务端解析 PDF 取页数**。否决：需要在服务端引入 PDF 解析依赖并下载完整文件，与「服务端不做重媒体处理」的既有约束冲突（同 V1 不在服务端跑 FFmpeg 的判断）。
