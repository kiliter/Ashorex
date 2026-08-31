# 移除服务端 AI 并导入课程学习内容设计

**日期：** 2026-08-31  
**状态：** 已完成口头评审，等待书面确认  
**范围：** Spring Boot 服务端、内部管理后台、Flutter iOS App、SQLite 迁移与部署配置

## 1. 背景与目标

V1 已实现服务端 LLM、ASR、MCP、自动转写、自动摘要和 Flutter AI 聊天入口。当前产品方向调整为：服务端只负责可信学习闭环与课程内容托管，不再运行任何 AI 能力；课程视频的全文文字和 AI 摘要由管理员在外部生成后批量导入，后续移动端 AI 由 Flutter 侧另行设计。

本次改造的目标是：

1. 完整删除服务端 AI Runtime 及其依赖、配置、接口和运维入口。
2. 删除现有 Flutter AI Tab、视频 AI 弹层及聊天基础设施。
3. 为课程后台增加 ZIP 批量导入全文文字与 Markdown 摘要的能力。
4. 为移动端提供单集学习内容只读接口。
5. 使用追加式迁移保留可复用的历史转写全文和全局摘要，同时删除不再使用的 AI 数据。

本次不实现移动端 AI，不在 Flutter 展示导入内容，也不引入新的自动摘要、搜索或问答能力。

## 2. 最终产品边界

### 2.1 服务端保留

- 身份、考试目标、课程、计划、欠债、可信观看、答题、专注、报表。
- Emby 同步、播放票据与媒体代理。
- 内部管理后台。
- 管理员维护的课程学习内容。
- 学习内容只读 API。

### 2.2 服务端删除

- LLM、ASR、MCP 客户端与运行时配置。
- LangChain4j 及所有 AI Chat、Tool、SSE 代码。
- FFmpeg 自动抽取音频、转写任务、分段摘要和自动全局摘要。
- AI 会话和消息持久化。
- 转写任务后台、AI/ASR/MCP 健康状态与环境变量。

### 2.3 Flutter 删除

- 底部 AI Tab。
- 播放页“问问视频 AI”入口和 Bottom Sheet。
- `ai_chat` Feature、SSE Parser、AI Repository、Controller、聊天模型和引用组件。
- `flutter_chat_ui` 等只由 AI 功能使用的依赖。

底部导航固定为：

```text
首页 / 学习 / 数据 / 我的
```

## 3. ZIP 导入协议

### 3.1 包结构

上传文件必须是 ZIP，根目录结构如下：

```text
manifest.json
lessons/
  emby-item-id-1/
    transcript.txt
    summary.md
  emby-item-id-2/
    transcript.txt
    summary.md
```

`manifest.json` 使用固定版本结构：

```json
{
  "version": 1,
  "lessons": [
    {"embyItemId": "emby-item-id-1"},
    {"embyItemId": "emby-item-id-2"}
  ]
}
```

路径由 `embyItemId` 推导，不允许在 manifest 中提供任意文件路径。`transcript.txt` 和 `summary.md` 均为 UTF-8 文本，去除首尾空白后不得为空。

### 3.2 匹配与校验

- 通过 `media_items.emby_item_id` 精确匹配课时。
- 所有 manifest 项必须属于当前上传页面对应的课程。
- manifest 中不允许重复 `embyItemId`。
- 每项必须同时存在 `transcript.txt` 和 `summary.md`。
- 先完成 manifest、路径、UTF-8、非空内容和课程归属的全包校验，再开始写数据库。
- ZIP 直接流式读入受限的内存数据结构，不解压到磁盘；拒绝绝对路径和 `..` 路径穿越。
- 压缩包最大 50 MiB，累计解压文本最大 100 MiB；超过限制整包拒绝，不新增对象存储或临时文件管理。

错误反馈显示具体的 Emby Item ID 和原因，但不返回服务器路径、堆栈或敏感配置。

### 3.3 事务语义

解析与校验在事务外完成；所有内容使用一个短数据库事务批量 Upsert：

- 任意一项校验失败：零写入。
- 任意一项数据库写入失败：整包回滚。
- 重复上传同一课时：覆盖 `full_text` 和 `summary_markdown`。
- 首次导入写入 `imported_at`；覆盖时保留该值，只更新 `updated_at`。

管理后台在课程课时页提供一个 ZIP 文件选择框和“导入学习内容”按钮。导入成功后显示导入数量；课时列表显示每集内容状态和最后更新时间，不增加独立的内容编辑器。

## 4. 数据模型与迁移

追加 `V013__replace_ai_with_lesson_study_contents.sql`，不修改 V010～V012。

新表：

```text
lesson_study_contents
  id                  TEXT PRIMARY KEY
  media_item_id       TEXT NOT NULL UNIQUE
  full_text           TEXT NOT NULL
  summary_markdown    TEXT NOT NULL
  imported_at         INTEGER NOT NULL
  updated_at          INTEGER NOT NULL
```

`media_item_id` 外键指向 `media_items(id)`，删除策略为 `ON DELETE RESTRICT`。

迁移顺序：

1. 创建 `lesson_study_contents`。
2. 按 `segment_index` 合并同一课时已有 `transcript_segments.text`，并迁入 `full_text`。
3. 将已有 `video_summaries.summary` 迁入 `summary_markdown`。
4. 对只有一侧历史数据的课时保留已有内容，缺失一侧写空字符串，等待管理员后续用完整 ZIP 覆盖；新上传仍严格要求两份非空文件。
5. 删除 FTS 触发器和虚拟表。
6. 删除 `ai_messages`、`ai_conversations`、`transcription_jobs`、`transcript_segments`、`video_section_summaries`、`video_summaries`。
7. 重建 `runtime_integration_settings`，只保留 Emby Base URL、API Key、用户 ID 和 `updated_at`，并迁移现有 Emby 值。

用户已接受删除已有 AI 聊天记录。迁移必须在临时 SQLite 集成测试中验证旧数据迁入、新表约束和废弃表删除。

## 5. 服务端组件

课程内容属于 `catalog`，不再保留 `ai` 模块。

- `LessonStudyContent`：课程学习内容领域数据。
- `LessonStudyContentRepository` / `JdbcLessonStudyContentRepository`：按课时读取和批量 Upsert。
- `LessonStudyContentZipParser`：在大小上限内解析 ZIP 并产生完全校验后的内存命令对象，不写磁盘、不访问数据库。
- `LessonStudyContentImportService`：校验课程归属并拥有批量写事务。
- `CourseAdminController`：接收 ADMIN + CSRF 保护的上传请求并返回课程课时页结果。
- `CatalogController` / `CatalogQueryService`：提供登录用户可读的单集内容接口。

Controller 不直接使用 `JdbcClient`，ZIP 解析器不负责事务，Repository 不包含业务校验。

## 6. API 契约

新增：

```text
GET /api/v1/lessons/{lessonId}/study-content
Authorization: Bearer <token>
```

成功响应：

```json
{
  "lessonId": "本地课时 UUID",
  "fullText": "完整全文",
  "summaryMarkdown": "# 摘要",
  "updatedAt": "2026-08-31T12:00:00Z"
}
```

规则：

- `lessonId` 使用本地 `media_items.id`，不是 Emby Item ID。
- 课时不存在或对移动端不可见时沿用课程接口的未找到语义。
- 课时存在但尚未导入内容时返回 RFC Problem Details，稳定错误码为 `LESSON_STUDY_CONTENT_NOT_FOUND`。
- 响应只读，不增加移动端写接口。
- 同步更新 `docs/api/openapi.yaml`，契约测试校验 DTO 和错误码。

## 7. 配置、依赖和运维收缩

- Maven 删除 LangChain4j BOM、OpenAI、MCP 以及只为 AI 使用的依赖。
- Docker 镜像删除 FFmpeg 安装与相关运行目录。
- CI 删除 LLM、ASR、MCP、FFmpeg Stub 或环境变量。
- `.env.example`、`application.yml`、Compose 和运行手册删除 LLM/ASR/MCP 配置。
- 运行时服务配置页只显示 Emby，仍保持数据库优先、环境变量首次默认、保存后新请求立即生效。
- 健康页面只显示核心服务、SQLite 和 Emby 状态。
- 删除 AI Provider、转写任务、MCP 和 Token 用量相关错误码、日志与指标。

## 8. 测试与验收

### 8.1 服务端

- ZIP 解析单元测试：正确包、缺文件、重复 ID、非法 UTF-8、空文件、非法路径和大小上限。
- 导入集成测试：课程归属、全包原子性、重复覆盖、时间字段和 ADMIN/CSRF。
- 迁移集成测试：历史全文/摘要迁移、旧 AI 表删除、Emby 配置保留。
- API 集成测试：已导入、未导入、无效课时和鉴权。
- 依赖检查：源码、Maven、配置和镜像中不再包含 LangChain4j、LLM、ASR、MCP、FFmpeg 运行能力。

### 8.2 Flutter

- Shell Widget 测试断言四个 Tab。
- 播放器测试断言不再出现 AI 按钮。
- 删除 AI 专属测试和依赖后运行 `flutter analyze` 与全部 Flutter 测试。

### 8.3 完成标准

```bash
make format
make verify
```

必须全部通过，并确认：

- 服务端没有 AI/ASR/MCP/自动转写入口。
- Flutter 没有 AI 入口或残留网络调用。
- ZIP 导入全包原子，重复上传可覆盖。
- 移动端只读接口和 OpenAPI 一致。
- V013 能从 V012 数据库平滑升级。
- 不包含密钥、临时 ZIP 或调试产物。
