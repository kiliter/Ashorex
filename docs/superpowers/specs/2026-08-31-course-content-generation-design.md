# 课程自动转写与摘要设计

**日期：** 2026-08-31
**状态：** 已完成口头评审，等待书面复核
**范围：** Spring Boot 服务端、SQLite、内部管理后台、课程学习内容只读 API

## 1. 目标与边界

服务端只恢复课程内容生产能力：从 Emby 获取课时音频，通过 OpenAI-compatible ASR 生成全文，再通过 OpenAI-compatible LLM 生成 Markdown 摘要。Flutter 和其他客户端只读查询结果。

本次实现：

- 课时级“AI 一下”，以及勾选多个课时后的“批量 AI 一下”完整工作流。
- 单独重新转写、重新摘要和重新出题只在课时内容详情页提供。
- 题目草稿逐课时审核，课程级支持批量通过、驳回和删除。
- 持久化串行任务、阶段日志、失败重试和耗时统计。
- ASR、LLM、OpenRouter 模型目录和自动扫描配置。
- OpenRouter 模型名称、上下文长度和最大输出 Tokens 的 SQLite 缓存。
- 定时补全任务，但默认关闭。
- 保留 ZIP 导入作为人工覆盖和故障兜底。

本次不实现：

- AI Chat、AI Tab、视频问答或 SSE 对话。
- 智能体、MCP、联网搜索和 Tool Calling。
- AI 制定计划、修改业务数据或评价学习记录。
- 模型部署、模型下载、GPU/MLX 进程管理。
- 向量数据库、FTS 问答和分段时间轴检索。

## 2. 方案选择

采用 SQLite 持久化任务表和单线程 Worker。同步 Controller 会产生长请求和超时；内存队列会在重启时丢失任务。当前服务只有一个实例且同时在线少于 5 人，不引入 Redis、Kafka 或分布式锁。

转写、摘要和 AI 出题是三个独立任务。转写成功立即保存全文，摘要失败不影响全文；已有人工全文也可以直接创建摘要或 AI 出题任务。

## 3. 完整数据流

### 3.1 转写

```text
管理员创建 TRANSCRIBE 任务
→ 查询课时与 Emby Item ID
→ POST /Items/{id}/PlaybackInfo 获取 MediaSourceId
→ GET /Audio/{id}/stream.mp3
→ 16 kHz / 单声道 / 64 kbps MP3 写入临时文件
→ multipart POST {ASR Base URL}/v1/audio/transcriptions
→ 逐行读取 NDJSON
→ 依次追加每行 text
→ 请求正常结束后一次性保存 full_text
→ 删除临时 MP3
```

ASR 请求字段：

```text
file
model
language
stream=true
chunk_duration=30
```

服务端只拼接响应的 `text`，不再次拼接 `accumulated`。空文本、流内 `error`、非 2xx、连接中断或超时均使任务失败，不覆盖已有全文。

### 3.2 摘要

```text
管理员创建 SUMMARIZE 任务
→ 校验当前课时已有全文
→ 读取所选模型上下文预算
→ 短全文：一次生成最终 Markdown
→ 长全文：顺序生成分段摘要
→ 合并分段摘要生成最终 Markdown
→ 一次性保存 summary_markdown
```

摘要固定输出中文 Markdown，结构为：

```text
# 课时摘要
## 核心内容
## 关键知识点
## 术语与概念
## 复习提示
```

全文作为不可信输入使用明确分隔符包裹。日志不记录完整全文、完整 Prompt 或上游完整响应。

### 3.3 AI 出题

```text
管理员创建 GENERATE_QUIZ 任务
→ 校验当前课时已有全文
→ 按模型上下文预算切分长全文
→ 每片生成候选知识点和候选题
→ 递归归并、去重并选出目标数量
→ 校验题型、选项、唯一答案和解析
→ 保存到不可供学习端查询的题目草稿
→ 管理员审核或课程级批量发布
```

摘要存在时作为课程重点参考，但不能代替全文。没有全文时禁止创建出题任务。

## 4. 任务状态与串行规则

转写状态：

```text
QUEUED → FETCHING_AUDIO → TRANSCRIBING → READY
任意执行状态 → FAILED
```

摘要状态：

```text
QUEUED → SUMMARIZING → READY
任意执行状态 → FAILED
```

出题状态：

```text
QUEUED → GENERATING_QUIZ → READY_FOR_REVIEW
任意执行状态 → FAILED
```

规则：

- 全局同时只运行一个内容任务。
- 队列按 `queued_at`、课程排序和课时排序稳定执行。
- 同一课时、同一任务类型只能有一个未完成任务。
- 批量转写默认跳过已有全文；批量摘要默认跳过已有摘要或没有全文的课时。
- 批量 AI 出题默认跳过没有全文或已有未发布草稿的课时。
- 重新转写或重新摘要必须显式确认并创建新任务。
- 新任务执行成功前保留旧内容；失败不清空旧内容。
- 不自动重试，管理员可对失败任务点击“重试”。
- 服务启动时将遗留执行态任务标记为 `FAILED/SERVER_RESTARTED`。
- 每个阶段只写短事务，外部 HTTP 调用期间不持有数据库事务。

## 5. 数据模型

追加 V014，不修改已经应用的 V013。

### 5.1 `lesson_study_contents`

重建现有表，使两类内容可以独立就绪：

```text
id
media_item_id              UNIQUE
full_text                  NULLABLE
summary_markdown           NULLABLE
transcript_updated_at      NULLABLE
summary_updated_at         NULLABLE
imported_at                NULLABLE
updated_at
```

两项都为空时不保留内容行。ZIP 导入仍要求全文与摘要同时非空，并同时更新两项。

### 5.2 `content_generation_jobs`

```text
id
course_id
media_item_id
job_type                   TRANSCRIBE / SUMMARIZE / GENERATE_QUIZ
status
queued_at
started_at
finished_at
audio_duration_ms
fetch_ms
transcribe_ms
summarize_ms
quiz_generate_ms
total_ms
asr_model
llm_model
prompt_tokens
completion_tokens
attempt
error_code
error_message
created_by
```

### 5.3 `content_generation_job_logs`

```text
id
job_id
occurred_at
level
stage
message
```

日志只记录阶段、数量、耗时和安全错误，不记录密钥、完整媒体 URL、正文、Prompt 或完整上游响应。

### 5.4 `llm_model_catalog`

```text
model_id                   PRIMARY KEY
display_name
context_length
max_completion_tokens
tokenizer
supported_parameters_json
fetched_at
active
```

刷新时 Upsert 当前目录，未返回的旧模型标记为 `active=false`，不直接删除。

### 5.5 AI 题目草稿

```text
quiz_generation_drafts
  id
  job_id
  course_id
  media_item_id
  status                    READY_FOR_REVIEW / PUBLISHED
  requested_question_count
  created_at
  published_at

quiz_generation_draft_items
  id
  draft_id
  question_type             SINGLE_CHOICE / TRUE_FALSE
  content
  explanation
  sort_order
  published_question_id     NULLABLE

quiz_generation_draft_options
  id
  draft_item_id
  content
  correct
  sort_order
```

草稿与正式题目分表。`published_question_id` 用于保证重复发布幂等，并支持从草稿追溯正式题目。

## 6. 外部服务配置

统一服务配置页增加：

### ASR

- Base URL。
- API Key，可为空。
- 模型名，默认 `mlx-community/Qwen3-ASR-1.7B-8bit`。
- 语言，默认 `Chinese`。
- Chunk Duration，默认 30 秒。
- 超时。
- 测试连接。

### LLM

- CPA/OpenAI-compatible Base URL。
- API Key。
- 从缓存搜索选择的模型 ID。
- 上下文长度。
- 最大输出 Tokens。
- 摘要超时。
- 测试连接。

### OpenRouter 模型目录

- 固定目录地址 `https://openrouter.ai/api/v1/models`。
- OpenRouter API Key。
- “刷新模型目录”操作。
- 缓存模型数量和最后刷新时间。
- 手动模型名、上下文和最大输出 Tokens 兜底。

缓存为空时，第一次进入配置页自动尝试加载一次；已有缓存时页面直接读取 SQLite，不为每次访问调用 OpenRouter。管理员可以随时手动刷新。OpenRouter 只提供目录；实际摘要和出题请求始终使用 LLM Base URL。模型目录刷新失败继续使用旧缓存，不影响已经配置好的任务。

### 自动补全

- `enabled=false`，默认关闭。
- 扫描间隔默认 15 分钟。
- 只处理缺失全文或摘要。
- 不覆盖已有内容。

## 7. 上下文预算

选择 OpenRouter 模型时自动载入：

```text
context_length
top_provider.max_completion_tokens
```

摘要输入预算：

```text
input_budget_tokens
= context_length
- configured_max_completion_tokens
- 2,048 safety tokens
```

模型 Tokenizer 无法在服务端可靠复现时，使用保守字符估算切片，不声称精确 Token 计数。实际调用完成后记录上游响应中的 `usage.prompt_tokens` 和 `usage.completion_tokens`。手动模型必须同时填写上下文长度；这样 CPA 自定义别名也可以参与预算计算。

长视频不允许把整篇全文强行放进一次请求。摘要和出题共用递归分层处理：

```text
按段落和句子边界切片
→ 每片生成内容提要 / 候选知识点 / 候选题
→ 按上下文预算分组归并
→ 结果仍超预算时继续递归归并
→ 生成最终摘要或指定数量的题目草稿
```

该流程只受配置的上下文长度和最大输出 Tokens 控制，不硬编码某个模型。任务保存模型 ID、上下文长度和输出上限快照，模型目录后续刷新不会改变正在执行的任务。

## 7.1 AI 题目草稿与发布

默认每课时生成 5 道题：4 道单选题和 1 道判断题；管理员创建任务时可在 1～20 题范围调整。每题必须包含题干、选项、唯一正确答案和中文解析。

模型支持 OpenRouter `supported_parameters` 中的 `structured_outputs` 或 `response_format` 时优先请求结构化 JSON；否则使用严格 JSON Prompt。服务端必须校验题型、选项数量、正确答案、正文、解析和课时归属。格式不合法时允许在同一任务内进行一次结构修复，仍不合法则任务失败。

AI 结果写入独立草稿表，不直接写正式题目。草稿允许管理员编辑、删除和选择。课程级“批量发布”执行：

1. 读取选中的所有 `READY_FOR_REVIEW` 草稿。
2. 在事务外完成完整校验和重复题干提示。
3. 在一个短事务中追加正式题目和选项，并将草稿标记为 `PUBLISHED`。
4. 任意草稿不合法时整批不发布。
5. 已发布草稿重复提交不产生重复题目，已有正式题目不被删除或覆盖。

## 8. 管理后台

视觉和交互严格沿用 `/Users/zhangjialin/Downloads/shangan-admin-prototype.html` 的高密度桌面后台风格，不重新设计另一套视觉语言。

全局导航：

```text
运行状态 / 用户管理 / 课程管理 / 内容任务 / 服务配置
```

课程页增加：

- 已转写数量。
- 已摘要数量。
- 批量转文本。
- 批量 AI 摘要。
- 批量 AI 出题。
- 查看题目草稿和批量发布。
- 查看该课程任务。

课时页增加：

- 转写状态和摘要状态。
- 一键转文本。
- AI 摘要。
- AI 出题。
- 查看内容。
- 查看任务日志。
- 已有内容时显示重新生成入口。

内容任务页增加：

- 当前任务、当前阶段、课时和运行时长。
- 排队、成功和失败数量。
- 类型、状态和课程筛选。
- 阶段耗时。
- 安全错误信息。
- 失败任务重试。

首页增加紧凑统计：

- 当前运行任务和阶段。
- 等待数量。
- 最近 24 小时成功与失败数量。
- 平均音频获取、转写、摘要和总耗时。
- `ASR RTF = 转写耗时 / 音频时长`。
- 最近任务日志。

## 9. 管理端接口

全部使用 ADMIN Session、CSRF 和 POST 表单，不新增公开写 API：

```text
POST /admin/lessons/{lessonId}/transcribe
POST /admin/lessons/{lessonId}/summarize
POST /admin/lessons/{lessonId}/generate-quiz
POST /admin/courses/{courseId}/transcribe
POST /admin/courses/{courseId}/summarize
POST /admin/courses/{courseId}/generate-quiz
GET  /admin/content-jobs
GET  /admin/content-jobs/{jobId}
POST /admin/content-jobs/{jobId}/retry
GET  /admin/courses/{courseId}/quiz-drafts
POST /admin/courses/{courseId}/quiz-drafts/publish
POST /admin/settings/models/refresh
```

## 10. Flutter 只读接口

保留：

```text
GET /api/v1/lessons/{lessonId}/study-content
```

为了表达独立任务的部分就绪状态，响应调整为：

```json
{
  "lessonId": "lesson-id",
  "transcriptStatus": "READY",
  "summaryStatus": "MISSING",
  "fullText": "完整全文",
  "summaryMarkdown": null,
  "transcriptUpdatedAt": "2026-08-31T12:00:00Z",
  "summaryUpdatedAt": null,
  "updatedAt": "2026-08-31T12:00:00Z"
}
```

全文和摘要都不存在时继续返回 `LESSON_STUDY_CONTENT_NOT_FOUND`。接口只读，不向 Flutter 暴露任何外部服务密钥、任务创建能力或 Emby URL。

## 11. 失败处理

- Emby PlaybackInfo 或音频流失败：`EMBY_AUDIO_UNAVAILABLE`。
- ASR 未配置：`ASR_NOT_CONFIGURED`。
- ASR 非 2xx、流内错误或空文本：`ASR_REQUEST_FAILED`。
- LLM 未配置：`LLM_NOT_CONFIGURED`。
- 没有全文时创建摘要：`TRANSCRIPT_NOT_READY`。
- LLM 非 2xx 或空摘要：`SUMMARY_REQUEST_FAILED`。
- 没有全文时创建题目：`TRANSCRIPT_NOT_READY`。
- LLM 题目响应为空或结构校验失败：`QUIZ_GENERATION_FAILED`。
- 批量发布包含失效草稿：`QUIZ_DRAFT_INVALID`。
- OpenRouter 刷新失败：保留旧缓存并显示安全错误，不影响摘要或出题任务。

错误消息可供管理员排查，但必须截断且不得包含 API Key、Authorization、Cookie、完整正文或堆栈。

## 12. 验收

- 实际 Emby 视频课时可直接取得完整音频，不下载视频。
- ASR NDJSON 多个 `text` 块按顺序拼接且不重复。
- 转写成功、摘要失败时全文仍可读取。
- 单课时按钮和课程批量按钮均只创建正确任务。
- 长视频摘要和 AI 出题能够递归分层处理，不超过配置的上下文预算。
- 摘要只忠实整理视频明确讲到的内容，不补充、联想、推断、评价或给出学习建议。
- AI 题目只进入草稿；逐课时审核和课程级批量发布均可用。
- 批量发布全批校验、事务写入且重复提交幂等，不覆盖已有正式题目。
- 批量任务按课时顺序、全局串行执行。
- ASR 与 LLM 调用分别进入独立单线程池，但 Worker 同步等待当前任务完成，转写、摘要和出题之间不得并发。
- 两个线程池周期打印 `IDLE/WAITING/RUNNING` 存活日志，日志只包含任务标识、阶段和线程池计数。
- 已有内容默认跳过，显式重新生成才覆盖。
- 定时任务代码存在但默认关闭；开启后只补缺失内容。
- OpenRouter 模型目录可刷新、缓存和离线读取。
- CPA 自定义模型可手动填写上下文长度。
- 临时 MP3 在成功、失败和超时后均删除。
- 首页和任务页展示真实阶段、日志与耗时。
- Flutter 只读接口能表达全文或摘要部分就绪。
- ZIP 导入继续可用。
- 服务端和 Flutter 都没有聊天、智能体、MCP 或 AI 业务写入口。
