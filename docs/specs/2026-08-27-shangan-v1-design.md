# 上岸 V1 产品与技术设计规范

**文档状态：** 已冻结，可进入实现
**版本：** V1.8
**日期：** 2026-09-03
**目标平台：** iPhone、iPad、Android
**工作仓库名：** `shangan`
**产品名：** 上岸

---

## 1. 文档目的

本文档定义“上岸”V1 的产品边界、业务规则、系统架构、模块职责、数据模型、接口原则、媒体播放方案、课程学习内容、测试要求、部署方式和验收标准。

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
编排并保存今日作战单
  ↓
计划课时学习 / 模拟考试 / 独立专注
  ↓
日终自动结算
  ↓
未完成承诺形成学习欠债，无活动日记为开摆日
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

#### Flutter 移动学习端

- 账号登录。
- 设置一个当前考试目标。
- 首页按「考试倒计时与课程进度压力 → 今日作战单 → 学习欠债与其它学习数据」展示；不单独展示「今日追赶中」。
- 学习 Tab 以作战日历查看和编排每日作战单；课程课时只在作战单编排区选择。
- 浏览课程与 Emby 剧集，入口位于作战单编排区，课程详情仍可从课时进入。
- 在作战单编排区集中选择课时和模拟考试，并原子保存或修改今日作战单。
- 观看 Emby 视频。
- 禁止跳到尚未验证观看的区间；允许回看已经验证的区间。
- 记录可信观看时间与可信最大进度。
- 视频中随机验活。
- 视频完成后答题。
- 首页右滑小工具中的番茄钟和练习计时。
- 从用户维护的考试预置创建模拟考试，倒计时结束或提前交卷后上传试卷照片。
- 已学课时可作为复习快捷入口，不参与作战单完成与欠债计算。
- 日终自动判定作战完成、作战未完成、自由学习或开摆日。
- 正常日终未完成的计划同样转为学习欠债。
- 查看学习欠债。
- 每日晚间审判。
- 每周学习报表。
- 登录后全局悬浮学习伙伴「毛线团团」：可拖动，全屏播放时贴边隐藏；点击只播放挥手动效。这不是 AI 入口，不提供对话或问答。

#### 服务端

- 模块化单体后端。
- SQLite 主数据库。
- Emby API 集成、课程同步、视频播放代理。
- 从 Emby 音频流生成课时全文，并通过 OpenAI-compatible LLM 生成 Markdown 摘要。
- 基于课时全文和摘要生成待审核课后题草稿，并由管理员批量发布。
- 持久化、全局串行的内容生成任务、阶段日志、失败重试和耗时统计。
- 题目配置与答题记录。
- 可信观看进度校验。
- 日计划、锁定、开摆、欠债、报表规则。
- 课程视频全文文字和 Markdown 摘要的存储与只读查询。
- 内部管理后台。
- 数据库迁移、健康检查、日志、备份和恢复。

#### 管理后台

- 管理员登录。
- 创建和禁用用户。
- 查看 Emby 连接状态。
- 从当前 Emby 用户可见列表选择媒体库，或手工绑定 Emby 剧集/目录为课程。
- Emby 媒体库删除后可将课程重新绑定到重建的媒体库，并保留原课时及其学习历史。
- 手动同步课程。
- 在已归档课程区域单个或批量物理删除课程完整关联数据图，并在提交前展示影响统计和二次确认。
- 启用、禁用和排序视频。
- 为视频配置单选题和判断题。
- 按课程批量导入每集全文文字和 Markdown 摘要。
- 按课时或课程批量触发转文本和 AI 摘要。
- 按课时或课程批量生成 AI 题目草稿，并支持课程级批量发布。
- 查看内容任务状态、阶段日志和耗时。
- 内容任务列表与详情只做局部自动刷新，所有任务、草稿、账号角色和内容状态使用中文显示。
- 查看基础运行状态。
- 配置 Emby、ASR、LLM 和 OpenRouter 模型目录，保存后对新请求立即生效。
- 使用内置“你好”MP3 测试 ASR，并使用最小提示词测试 LLM 连通性。

### 3.2 明确不做

- Windows 或 macOS 客户端。
- PC 学习 Web。
- 离线视频下载和离线学习。
- 付费、订阅、商城、课程售卖。
- 直播。
- DRM。
- 排行榜、社交、研友、监督人。
- 推送通知和 Live Activity。
- 服务端 AI 聊天、智能体、MCP、联网搜索和 AI 业务写操作。
- Flutter AI Tab 和视频 AI 问答；后续移动端 AI 另行设计。全局学习伙伴只是本地精灵动画，不是 AI 入口。
- 多智能体。
- 向量数据库。
- 微服务、Redis、Kafka、对象存储和 Kubernetes。
- 主观题 AI 批改。
- App Store 正式发布自动化；V1 首先以真机和 TestFlight 验收。

---

## 4. 核心业务规则

### 4.1 考试目标

每个用户可以保存多个考试目标。首页按考试日期从近到远以内部 Tab 切换进度。

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

### 4.2 今日作战单

今日作战单状态：

```text
ACTIVE → COMPLETED
       ↘ CLOSED_WITH_DEBT
```

规则：

- 未保存内容只存在于客户端作战单编排区，不是服务端业务状态。
- 首次保存完整编排结果后创建 ACTIVE 作战单；后续通过“修改作战单”加载当前快照。
- 每次保存携带当前版本号，并在一个事务中校验、替换未开始项目、递增版本和保存修订审计；冲突返回稳定错误码。
- 不限制每日修改次数。
- 未开始项目可删除，删除后日终不再产生欠债；正在执行、等待上传材料或已完成项目不可删除或修改。
- 作战单即使当前所有项目完成也保持 ACTIVE，允许当天继续增加项目；仅在日终进入终态。
- 只有用户时区下的当天、且尚未进入日终终态的作战单可以保存；历史日只读并展示欠债标记。
- 日终关闭和欠债生成必须幂等，重复执行不能生成重复欠债。

作战单项目类型：

- `VIDEO`：观看指定未学完课时，并完成必答课后题。
- `MOCK_EXAM`：使用考试预置的名称和时长快照倒计时，提交试卷照片后完成。
- `REVIEW_SHORTCUT`：已学课时的复习快捷入口，不是任务，不参与完成率和欠债。
- `DEBT_REPAYMENT`：偿还历史欠债，内部仍引用原始任务类型。

编排规则：

- 课时和模拟考试只能从作战单编排区加入；课程详情不提供“加入计划”按钮。
- 同一课时同一天只能出现一次。
- 已学完课时加入后自动成为 `REVIEW_SHORTCUT`。
- 选择器中已经加入今日作战单的课时和考试不可再点，避免重复加入；未开始项目从作战单列表删除。已经开始的不可变项目不能删除。
- 视频学习和复习快捷入口严格按课程及课时固有顺序排列，不提供手动调序；模拟考试排列在课时之后。客户端可按课程分组展开或折叠，不改变服务端顺序。
- 模拟考试加入前必须选择一个考试预置；保存项目时复制预置名称和时长，之后修改预置不影响作战单历史。
- 完整快照中的任意一项校验失败时，整次保存不得产生部分修改。

### 4.3 学习欠债

欠债必须记录“欠了什么”，不能只记录分钟数。

每笔欠债包括：

- 原始计划任务。
- 类型。
- 关联视频、题目或模拟考试。
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
- `FOCUS`：计划内专注任务尚未完成。

规则：

- 日终时，仍存在于作战单的未完成视频、答题和计划内专注任务按未满足的组成部分生成欠债。
- VIDEO 任务可以同时生成一笔 `VIDEO_WATCH` 欠债和一笔 `QUIZ` 欠债。
- 视频债务的剩余量以视频可信进度计算。
- QUIZ 欠债使用 600 秒作为 V1 计划压力估值；提交完整答卷即结清，不按真实答题秒数扣减。
- 模拟考试属于复习任务，只保留倒计时、附件和审计记录，不纳入进度或欠债计算。
- 已删除的未开始项目、`REVIEW_SHORTCUT` 和独立专注计时不生成欠债。
- `DEBT_REPAYMENT` 任务再次未完成时，不创建嵌套欠债，只保留并更新原欠债。
- 同一 `source_plan_item_id + debt_type` 只能生成一笔欠债。
- 后续可信观看同一视频会自动减少对应 `VIDEO_WATCH` 欠债；完成同一视频的完整答题会自动结清对应 `QUIZ` 欠债。
- 用户创建下一日计划时，未还欠债显示在新任务之前，并默认推荐加入计划，但不自动锁定。
- 完成关联的还债任务后，对应欠债减少或结清。
- V1 客户端不能直接核销欠债。

### 4.4 日终结果

客户端不提供手动“开摆”操作。服务端在用户时区的日终时间后自动判定：

```text
有作战单且任务全部完成       → COMPLETED / 作战完成
有作战单且存在未完成任务     → CLOSED_WITH_DEBT / 作战未完成
无有效作战单但存在学习活动   → FREE_STUDY / 自由学习
无有效作战单且无学习活动     → SLACKED / 开摆日
```

有效学习活动包括可信观看、模拟考试和独立专注记录。复习审计事件只用于周报列举，不单独阻止“开摆日”判定。

规则：

- 作战未完成时为当前仍存在的未完成项目生成欠债。
- 自由学习和开摆日不生成欠债。
- 调度器重复执行必须幂等；服务停机跨过日终后，重新启动必须补算遗漏日期。
- 历史 `ABANDONED` 计划和开摆记录继续只读兼容，但不再生成新的手动开摆数据。

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

播放与作战单关联：

- 播放不在今日作战单中的未完成课时前，App 必须说明“本次观看不会完成今日作战单”，并提供继续观看、前往修改作战单和取消。
- 用户选择继续观看时仍创建可信观看会话、更新课时学习进度并进入日报学习时长，但不自动创建或完成作战单项目。
- `REVIEW_SHORTCUT` 只允许关联已经可信完成的课时，因此整个视频已处于可回看区间，可自由拖动。
- 复习播放不更新作战单项目，不参与完成率、欠债和有效学习时长；同一播放会话第一次有效心跳只记录一次复习审计事件。

### 4.6 按视频进度百分比验活

随机验活由服务端驱动。

流程：

1. 创建观看会话时，服务端从当前可信最大位置按用户设置的百分比生成下一验活检查点。
2. 心跳达到阈值时，服务端返回 `aliveCheckRequired=true`。
3. App 立即暂停并弹出验活对话框。
4. 用户必须在 60 秒内确认。
5. 超时后保持暂停，并记录一次失败。
6. 验活等待期间不累计可信时间。
7. 确认后服务端从最新可信最大位置生成下一检查点；检查点达到视频完成阈值时不再排期。

所有用户都可以关闭验活或使用滑杆设置 `1%～50%` 的视频进度间隔，默认 `50%`。滑杆实时显示“每推进 X% 验活一次”。检查点按视频内容位置计算，不按墙钟时间计算。

心跳必须携带当前倍速，合法值为 `1.0`、`1.25`、`1.5` 和 `2.0`。服务端按倍速校验允许的位置增量，但有效学习时长仍按真实经过时间累计。

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
- AI 可以根据当前课时全文和摘要生成单选、判断题草稿，但不能直接发布正式题目。
- 管理员可以编辑草稿并在课程范围批量发布；发布前全批校验，任意草稿无效时整批不写入。
- 批量发布只追加题目，不删除或覆盖已有正式题目；重复发布同一草稿必须幂等。

### 4.8 专注计时

类型：

- `POMODORO`。
- `PRACTICE`。

规则：

- 服务端记录开始、暂停、继续和结束时间。
- 移动端根据服务端返回的时间戳渲染倒计时，不能只依赖本地递减。
- App 切后台后继续计时。
- 用户主动取消时不计为完成。
- 专注计时位于首页右滑小工具菜单，也必须提供可发现的“小工具”按钮。
- 点击专注计时后弹出时长选择：可点 15 分钟、30 分钟、1 小时、2 小时预设，也可在圆形选择器上滑动自定义 1–720 分钟；选定后按该时长开始倒计时，不固定 25 分钟。
- 计划时长走完时弹出不可点外部关闭的到时提醒，伴随闹钟震动动画和铃声；用户确认后关闭。
- 专注进行中返回上一页须二次确认；确认后立即结束本次计时并离开，取消则留在本页。进行中保持屏幕常亮，并提示不要切到后台。系统无法禁止 Home，切后台或进程被回收后重新打开仍按服务端时间继续。
- 从后台回到专注页时，若仍在 `RUNNING`，提示计时未暂停。
- 专注计时不绑定作战单项目，不生成欠债；有效时长进入日报和周报。

模拟考试不复用专注计时状态机。用户在系统设置中维护考试预置，每个预置包含名称、时长和排序。模拟考试项目保存预置快照，并使用以下状态：

系统为已有用户和新建用户幂等补齐“行测 120 分钟”“申论 180 分钟”“大作文 180 分钟”三个默认预置；前两项采用国考官方时限，大作文采用产品约定的完整专项模拟时长，不是独立官方考试科目。用户可修改或删除，列表读取不会重复创建已主动删除的预置。

```text
PENDING → RUNNING → AWAITING_UPLOAD → COMPLETED
                  ↘ CANCELLED
```

- `RUNNING` 的截止时间由服务端时间计算，切后台、关闭 App 和重新打开后继续倒计时。
- 考试页在 `RUNNING` 期间返回上一页须二次确认；确认后立即提前交卷停止倒计时并离开，取消则留在本页。`RUNNING` 与 `AWAITING_UPLOAD` 期间保持屏幕常亮，并提示不要切到后台。系统无法禁止 Home，切后台或进程被回收后重新打开仍按服务端截止时间继续。
- 从后台回到考试页时，若仍在 `RUNNING`，提示考试计时未暂停。
- 用户可以提前交卷，提前交卷与自然到时都进入 `AWAITING_UPLOAD`。
- 自然到时弹出不可点外部关闭的到时提醒，伴随闹钟震动动画和铃声；用户确认后关闭。
- `AWAITING_UPLOAD` 与 `COMPLETED` 允许重考：在原会话上按预置快照时长重新开始倒计时，不取消作战单完成状态，已上传试卷照片保留。
- 至少上传一张试卷照片才进入 `COMPLETED`；支持 JPEG、PNG、HEIC，最多 9 张，单张不超过 10 MiB。
- 服务端校验文件签名、扩展名、所有权和大小，不依赖客户端 MIME 声明。
- 附件保存在本地数据目录，元数据和校验值保存在 SQLite，并纳入备份、恢复和孤儿文件清理。
- V1 不做 OCR、AI 阅卷、自动评分或 AI 业务写入。

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
- 日终结果：作战完成、作战未完成、自由学习或开摆日。
- 模拟考试完成数和待上传数。
- 新增欠债。
- 历史未还欠债。

晚间审判由规则模板生成，示例规则：

- 完成率大于等于 90%：肯定完成情况。
- 完成率 60% 到 90%：指出主要未完成项。
- 完成率低于 60%：明确新增欠债和进度风险。
- 开摆日：指出当天没有有效作战单和学习活动。
- 连续 3 天新增欠债：提示债务连续增长。

周报指标：

- 每日有效学习趋势。
- 视频、专注、答题总量。
- 正确率。
- 计划完成率。
- 新增和偿还欠债。
- 开摆日数量。
- 本周复习过的课时与复习次数，只做审计列举，不计完成率和有效学习时长。
- 验活失败次数。
- 与上周对比。

日报页面通过系统日期选择器直接选择日期，并提供“回到今天”；不使用左右逐日切换。周报通过日期选择器选择任意日期后归一到该周周一，显示完整周区间并提供“回到本周”；不使用左右逐周切换。

### 4.10 课程学习内容

课程视频可以关联一份完整全文文字和一份 Markdown 摘要。内容可以由管理员 ZIP 导入，也可以由服务端通过 Emby 音频流、OpenAI-compatible ASR 和 OpenAI-compatible LLM 生成。

规则：

- 课时列表只提供“AI 一下”，课程页支持勾选多个课时后“批量 AI 一下”；单阶段重新生成只放在课时内容详情页。
- 转写和摘要是独立持久化任务；摘要必须已有全文。
- 所有内容任务全局串行，批量任务按课时排序执行。
- ASR 与 LLM 外部调用使用两个独立单线程池，但 Worker 必须等待当前调用完成后再消费下一条任务，不允许转写、摘要和出题并发执行。
- ASR 与 LLM 线程池周期打印不含正文和密钥的存活日志，明确当前为 `IDLE`、`WAITING` 或 `RUNNING`。
- 已有内容默认跳过；重新生成必须显式触发，失败不得清空旧内容。
- 定时补全任务实现但默认关闭；开启后只处理缺失内容，不覆盖已有内容。
- 转写直接请求 Emby 音频流，不下载完整视频，不在上岸服务端执行 FFmpeg。
- ASR 流式 NDJSON 响应按顺序收集 `text` 并拼接，完整成功后一次性保存全文。
- 长全文根据所选模型上下文预算分段摘要，再合并最终 Markdown 摘要。
- 摘要只整理视频全文明确讲到的内容，不联想、不推断、不评价，不补充视频中未出现的知识、结论或学习建议。
- 管理员仍可按课程上传 ZIP 包作为人工覆盖和故障兜底。
- 题目草稿支持课程级批量通过、驳回和删除；通过即校验后发布，驳回保留记录，删除只允许未发布草稿。
- ZIP 中每集通过当前 Emby Item ID 与本地课时精确匹配；课时完成外部 Item ID 映射后，既有全文和摘要继续绑定不可变的本地课时 ID。
- 每集必须同时提供 UTF-8 `transcript.txt` 和 `summary.md`。
- 全包校验成功后一次性写入；任意错误均不产生部分导入。
- 重复上传覆盖对应课时旧内容。
- App 只能通过受保护的只读接口获取内容，V1 不提供移动端编辑或 AI 问答。

---

## 5. 总体架构

```text
┌──────────────────────────────────────┐
│        Flutter Mobile App            │
│       iPhone / iPad / Android        │
│                                      │
│ 首页 / 学习 / 数据 / 我的            │
│ 视频播放器 / 题目 / 计时 / 报表       │
└───────────────┬──────────────────────┘
                │ HTTPS REST
                ▼
┌──────────────────────────────────────┐
│       Spring Boot 模块化单体          │
│                                      │
│ Identity  Exam      Catalog          │
│ Planning  Debt      Learning         │
│ Quiz      Focus     Reporting        │
│     AI Content      Emby      Admin  │
└───────┬─────────────┬───────────────┘
        │             │
        ▼             ▼              ▼
     SQLite          Emby       ASR / LLM
        │             │              │
        │             └─ 视频 / 音频转码流
        │                            └─ OpenAI-compatible API
        └─ 业务数据 / 任务 / 全文 / 摘要 / 模型目录缓存
```

部署原则：

- 一个后端实例。
- 一个本机 SQLite 文件。
- SQLite 文件不得放在 NFS 或远程共享盘。
- Emby 可以是同机或独立 NAS。
- Emby 配置的初始值来自服务端环境变量；管理员保存后可保存在服务端 SQLite 中。
- iOS 不接触 Emby API Key。

---

## 6. 技术基线

截至 2026-08-27 的冻结基线：

| 区域 | 选择 |
|---|---|
| 移动客户端 | Flutter 3.44.7 stable，使用 FVM 固定版本 |
| Dart | 3.12.x，项目约束 `>=3.12.0 <4.0.0` |
| iOS 最低版本 | iOS 16 |
| Android 最低版本 | API 24 |
| Android 编译版本 | compileSdk 37 |
| Dart 状态管理 | flutter_riverpod 3.0.2，不使用代码生成 |
| 路由 | go_router 17.5.0 |
| HTTP | Dio 5.11.0 |
| Token 存储 | flutter_secure_storage 11.0.0 |
| 视频 | Flutter 官方 video_player 2.14.0 |
| 后端 | Java 21 LTS + Spring Boot 4.1.1 |
| Web 模型 | Spring MVC + Virtual Threads |
| 数据访问 | Spring JdbcClient / NamedParameterJdbcTemplate |
| 数据库 | SQLite + WAL，sqlite-jdbc 3.53.4.0 |
| 迁移 | Flyway Core 13.3.0 + flyway-database-nc-sqlite 13.3.0 |
| Java 格式化 | Spotless Maven Plugin 3.10.0 + Google Java Format |
| 管理后台 | Spring MVC + Thymeleaf + 少量原生 JavaScript |
| LLM SDK | LangChain4j 1.19.x OpenAI-compatible ChatModel，不使用 Agentic 模块 |
| API 文档 | springdoc-openapi 3.1.0 |
| 构建 | Maven Wrapper、FVM |
| 部署 | Docker Compose 或 systemd + Caddy |

版本规则：

- 核心框架使用上述精确版本；如初始化时无法解析，先查明原因，不得静默换版本。
- Flutter 提交 `pubspec.lock`。
- Maven 使用 BOM 管理依赖；Flyway Core 与 SQLite 数据库模块必须保持完全相同的版本。
- 禁止使用 milestone、RC 和 SNAPSHOT。
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
│   └── ios/                 # 历史目录名，实际承载 Flutter 多移动端工程
│       ├── .fvmrc
│       ├── pubspec.yaml
│       ├── pubspec.lock
│       ├── ios/
│       ├── android/
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
- Flutter 按 Feature First 组织，iPhone、iPad 和 Android 共享业务实现。
- 管理后台与后端同包部署，不单独创建 Vue 项目。
- API 统一 `/api/v1`。
- Android 与 iPhone、iPad 复用同一 Flutter 工程。
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
│   └── content
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
| `catalog` | 课程和视频本地快照、课时全文与摘要 |
| `planning` | 每日计划、任务和锁定状态机 |
| `debt` | 欠债生成、偿还、查询 |
| `learning` | 观看会话、可信进度、验活、播放票据 |
| `quiz` | 题目、选项、答题记录 |
| `focus` | 番茄钟和练习计时 |
| `reporting` | 日报、周报、晚间审判 |
| `ai.content` | 课时转写、摘要、任务队列、OpenRouter 模型目录缓存 |
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

课程全文、摘要、生成任务、阶段日志和模型目录缓存直接存 SQLite；临时音频只写本机临时目录并在任务结束后删除，不引入对象存储。

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
- `alive_check_interval_percent`，非空，所有用户可设置 1～50，默认 50
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
- `emby_item_type`：Movie / Episode / Video
- `source_fingerprint`：可空；由规范化媒体路径计算的版本化 SHA-256，不保存原始路径
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

唯一约束：`emby_item_id`；非空 `source_fingerprint` 也必须唯一。

`id` 是上岸内部不可变课时身份；确认同一物理媒体在 Emby 中取得新 Item ID 时，只更新 `emby_item_id` 和远端快照字段，不修改 `id` 或任何业务外键。

#### `media_item_source_mappings`

- `id`
- `media_item_id`
- `old_emby_item_id`
- `new_emby_item_id`
- `match_type`：SOURCE_FINGERPRINT / UNIQUE_LEGACY_METADATA / ADMIN_CONFIRMED
- `mapped_at`

映射审计不得保存 Emby 原始媒体路径。

### 10.3 每日计划

#### `daily_plans`

- `id`
- `user_id`
- `plan_date`
- `status`
- `version`，每次完整保存递增
- `activated_at`
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
- `mock_exam_preset_id`
- `mock_exam_name_snapshot`
- `planned_seconds`
- `completed_seconds`
- `watch_completed`
- `quiz_required`，在课时项目首次保存时快照
- `quiz_completed`
- `status`
- `sort_order`
- `completed_at`
- `created_at`
- `updated_at`

唯一约束：同一计划中 `VIDEO` 或 `REVIEW_SHORTCUT` 的 `media_item_id` 不重复。

#### `daily_plan_revisions`

- `id`
- `plan_id`
- `version`
- `items_snapshot_json`
- `created_at`

每次保存完整作战单后写入一条审计快照；快照不作为当前业务读取来源。

#### `mock_exam_presets`

- `id`
- `user_id`
- `name`
- `duration_seconds`
- `sort_order`
- `created_at`
- `updated_at`

#### `mock_exam_sessions`

- `id`
- `user_id`
- `plan_item_id`
- `name_snapshot`
- `duration_seconds_snapshot`
- `status`
- `started_at`
- `deadline_at`
- `submitted_at`
- `completed_at`
- `created_at`
- `updated_at`

#### `mock_exam_attachments`

- `id`
- `session_id`
- `user_id`
- `storage_path`
- `original_filename`
- `content_type`
- `size_bytes`
- `sha256`
- `sort_order`
- `created_at`

#### `lesson_review_events`

- `id`
- `user_id`
- `media_item_id`
- `watch_session_id`
- `reviewed_on`
- `created_at`

唯一约束：`watch_session_id`，保证一次复习播放会话只记录一次。

#### `plan_abandonments`

- `id`
- `plan_id`
- `user_id`
- `reason_code`
- `reason_text`
- `remaining_seconds`
- `created_at`

该表仅用于兼容历史数据，V1.3 不再写入新记录。

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
- `alive_check_due_position_ms`
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
- `day_outcome`
- `payload_json`
- `judgment_text`
- `generated_at`

唯一约束：`user_id + report_date`。

周报 V1 可实时查询，不强制落表。

### 10.9 课程学习内容与运行时配置

#### `lesson_study_contents`

- `id`
- `media_item_id`，唯一
- `full_text`，可空
- `summary_markdown`，可空
- `transcript_updated_at`，可空
- `summary_updated_at`，可空
- `imported_at`，可空
- `updated_at`

全文和摘要可由独立任务分别生成。ZIP 导入仍要求全文和摘要同时非空；重复导入保留 `imported_at` 并同时覆盖两项内容。

#### `content_generation_jobs`

- `id`
- `course_id`
- `media_item_id`
- `job_type`：TRANSCRIBE / SUMMARIZE / GENERATE_QUIZ
- `status`
- `queued_at`
- `started_at`
- `finished_at`
- `audio_duration_ms`
- `fetch_ms`
- `transcribe_ms`
- `summarize_ms`
- `quiz_generate_ms`
- `total_ms`
- `asr_model`
- `llm_model`
- `prompt_tokens`
- `completion_tokens`
- `attempt`
- `error_code`
- `error_message`
- `created_by`

同一课时、同一类型只能有一个未完成任务；一个服务实例全局同时只执行一个任务。

#### `content_generation_job_logs`

- `id`
- `job_id`
- `occurred_at`
- `level`
- `stage`
- `message`

#### `llm_model_catalog`

- `model_id`，主键
- `display_name`
- `context_length`
- `max_completion_tokens`
- `tokenizer`
- `supported_parameters_json`
- `fetched_at`
- `active`

目录数据来自固定 OpenRouter Models API；实际摘要请求仍使用管理员配置的 LLM Base URL。
选择目录模型时只使用 `/` 后的模型名，不把 OpenRouter 供应商前缀发送给实际 CPA 上游。

#### `quiz_generation_drafts`

- `id`
- `job_id`
- `course_id`
- `media_item_id`
- `status`：READY_FOR_REVIEW / PUBLISHED
- `requested_question_count`
- `created_at`
- `published_at`

草稿题目和选项使用独立明细表，字段与正式 `questions`、`question_options` 对齐，并保存可空的 `published_question_id` 保证重复发布幂等。草稿不能被学习端查询。

#### `runtime_integration_settings`

- `id`，固定为 `default`
- Emby Base URL、API Key、用户 ID
- ASR Base URL、API Key、模型、语言、超时
- LLM Base URL、API Key、模型、上下文长度、最大输出 Tokens、思考等级、超时
- OpenRouter API Key
- 内容自动补全开关，默认关闭
- 自动补全扫描间隔
- `updated_at`

表中不存在记录时使用环境变量初始值；一旦管理员保存，数据库中的整份配置成为运行时来源。

V013 将 V010 的历史转写片段按顺序合并为全文，将历史全局摘要迁入新表，并删除旧转写、摘要、FTS、聊天表。V012 的运行时配置表在 V013 中重建为仅含 Emby 字段。V014 以追加式迁移恢复内容生成配置和任务表，并调整课程内容表以允许全文和摘要独立就绪。

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

偏好响应包含 `aliveCheckEnabled` 和 `aliveCheckIntervalPercent`。所有用户都可保存 1～50 的进度间隔，默认 50。

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
GET /api/v1/lessons/{lessonId}/study-content
```

#### 每日计划

```text
GET /api/v1/plans?from={date}&to={date}
GET /api/v1/plans/{date}
PUT /api/v1/plans/{date}
```

`GET /api/v1/plans` 返回日期范围内已有作战单的状态摘要，供学习日历绘制完成打勾和欠债标记；`from <= to`，跨度不超过 62 天。

`PUT` 只接受用户时区下的当天日期，接收 `expectedVersion` 和完整项目数组，在一个事务中保存；版本不一致返回 `PLAN_VERSION_CONFLICT`，非当天返回 `PLAN_DATE_NOT_EDITABLE`。只能替换未开始项目，服务端保留不可修改项目并校验客户端没有删除或篡改它们。

#### 模拟考试预置与执行

```text
GET    /api/v1/mock-exam-presets
POST   /api/v1/mock-exam-presets
PUT    /api/v1/mock-exam-presets/{presetId}
DELETE /api/v1/mock-exam-presets/{presetId}
POST   /api/v1/mock-exams/{planItemId}/start
POST   /api/v1/mock-exams/{sessionId}/submit-early
POST   /api/v1/mock-exams/{sessionId}/retake
POST   /api/v1/mock-exams/{sessionId}/attachments
GET    /api/v1/mock-exams/{sessionId}
GET    /api/v1/mock-exams/{sessionId}/attachments/{attachmentId}
```

预置更新不级联修改作战单快照；附件上传使用 multipart，所有读取和写入都校验当前用户所有权。

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

专注会话不再接受 `planItemId`，也不更新作战单或欠债。

#### 报表

```text
GET /api/v1/reports/daily?date=YYYY-MM-DD
GET /api/v1/reports/weekly?weekStart=YYYY-MM-DD
```

---

## 12. Emby 集成

### 12.1 配置

服务端环境变量提供初始配置：

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
- 管理员可在 `/admin/settings/integrations` 修改配置，并绑定多个可用媒体库；每个媒体库选择“剧集”“电影”或“混合”类型，保存后新请求立即使用新配置。

### 12.2 同步

管理员先在 Emby 服务配置中绑定一个或多个配置用户可见的顶层视频媒体库，并为每个媒体库选择“剧集”“电影”或“混合”类型。课程页默认在这些已绑定媒体库内联想具体 `Series` 或 `Movie`，不铺开全部媒体库。该联想范围不构成后端来源类型硬限制，媒体库、Folder 和手工 Item ID 继续兼容。

课程创建和重新绑定使用统一的 Emby 来源选择器。选择器只在已绑定媒体库内按关键字分页联想符合绑定类型的 `Series` 或 `Movie`，不铺开媒体库；候选只包含名称、Item ID、类型和必要父节点 ID，不包含原始媒体路径。创建和重新绑定保留手工 ID 兼容兜底，服务端继续接受媒体库与 Folder。

单个联想来源支持“添加并同步”；最多可选择 50 个来源批量创建课程并按顺序串行同步。批量提交先验证全部来源，再在短事务中创建或恢复课程；单门同步失败只记录该课程错误，不回滚其他课程。活动课程重复来源幂等跳过，已归档的同来源课程恢复原身份。

同步流程：

1. 先验证父节点仍存在且配置用户有权访问；不存在或无权访问时保留最后一次可用快照。
2. 使用 `/Users/{EMBY_USER_ID}/Items` 按页递归读取父节点下全部 `Movie`、`Episode` 和 `Video`，只接受非 Folder 的视频媒体。
3. 全部分页成功后按 Emby Item ID 去重并生成稳定顺序；任一页失败时不写入部分结果。
4. 更新本地 `media_items` 快照，保留本地启用状态、排序、题目和全部学习业务关系。
5. 新视频默认启用。
6. 父节点有效时，Emby 删除的视频在本地标记为不可用，不级联删除学习记录。
7. 定时每 15 分钟同步，也支持管理员手动同步。

删除并重建媒体库后的重新绑定规则：

1. `media_items.id` 始终不变，`emby_item_id` 只代表当前 Emby 播放源。
2. 同步只保存由 Emby `Path` 规范化后计算的版本化 SHA-256 来源指纹，不保存、展示或记录原始路径。
3. 优先按当前 Item ID 匹配，其次按课程内唯一来源指纹匹配。
4. 对没有来源指纹的历史课时，仅当规范化标题完全一致、时长差不超过 2 秒，且新旧候选均唯一时自动兼容映射。
5. 自动映射只原位更新当前 Emby Item ID 和远端元数据，因此可信进度、作战单、欠债、题目、全文、摘要和内容任务继续关联原本地课时。
6. 任何一对多或多对一歧义都不得自动合并；重新绑定不写入部分结果，管理员必须明确确认映射后再提交。
7. Item ID 变化写入不含路径的映射审计记录。

课程删除规则：

1. 活动课程的“归档课程”只执行逻辑归档，将课程 `enabled` 设为 `false`，不得物理删除课程、课时和学习历史。
2. App 目录、计划新增和定时 Emby 同步忽略已归档课程。
3. 后台提供已归档课程视图和恢复操作；恢复沿用原课程 ID、课时 ID 与全部历史关系。
4. 已归档课程区域支持单个删除、勾选、全选当前列表和最多 50 门课程的批量删除。
5. 删除已归档课程是不可恢复的物理删除，必须删除课程、课时以及直接或间接关联的考试目标绑定、计划项目、欠债和偿还、观看会话和验活、可信进度、复习记录、题目和答题、全文和摘要、内容任务和日志、题目草稿、来源映射及课程移除审计。
6. 包含被删除计划项目的作战单修订快照必须删除或重建；受删除数据影响日期的日报快照和日终结果必须失效并按剩余数据重新生成，不得继续统计已删除课程的数据。
7. 单个与批量删除均必须先取得实时影响预览并二次确认，展示课程、课时和各类关联记录数量，明确说明删除后不可恢复；任一课程不是已归档状态、预览失效或删除失败时整批不写入。
8. 删除只作用于目标课程关联图，不删除同一用户、考试目标、作战单容器、模拟考试预置或其他课程数据；一个作战单包含多门课程时只删除目标课程相关项目。
9. 删除成功后重新添加相同 Emby 来源必须创建新的课程 ID 和课时 ID，不得恢复 `removed_at` 对应的旧身份。

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

## 13. 课程学习内容生成与导入

### 13.1 自动生成

转写任务从 Emby `/Audio/{Id}/stream.mp3` 获取 16 kHz、单声道、64 kbps 的完整音频流，写入临时 MP3 后调用 OpenAI-compatible `/v1/audio/transcriptions`。对 mlx-audio 使用 `stream=true` 和默认 30 秒 `chunk_duration`，只按顺序拼接每个 NDJSON 对象的 `text` 字段。完整请求成功后一次性保存全文；任意失败不覆盖旧全文。

摘要任务只读取当前课时全文，通过稳定版 LangChain4j OpenAI-compatible ChatModel 调用 `/v1/chat/completions`。根据 OpenRouter 缓存或手动配置的上下文长度计算输入预算；长全文使用递归分层处理，先生成分段摘要，再按预算逐层归并为最终 Markdown。完整成功后一次性保存摘要；失败不影响全文或旧摘要。

AI 出题任务读取当前课时全文和可选摘要，使用同一上下文预算和递归分层处理器从各片段提取候选知识点和候选题，再逐层归并、去重并选出目标数量。结果必须通过题型、选项、唯一正确答案、解析和归属校验后写入独立草稿。模型支持结构化输出时优先使用；否则使用严格 JSON 并允许在同一任务内进行一次格式修复。

临时音频无论成功、失败或超时都必须删除。所有外部调用有超时，不记录完整正文、Prompt 或上游响应。

### 13.2 ZIP 结构

```text
manifest.json
lessons/{embyItemId}/transcript.txt
lessons/{embyItemId}/summary.md
```

`manifest.json` 固定包含 `version: 1` 和 `lessons` 数组，每项只包含 `embyItemId`。文件路径由该 ID 推导，不接受任意路径配置。

### 13.3 校验与写入

- Emby Item ID 必须精确匹配当前课程的课时。
- manifest 中不允许重复 ID。
- 全文和摘要必须是非空 UTF-8 文本。
- ZIP 不解压到磁盘，拒绝绝对路径和路径穿越。
- 压缩包最大 50 MiB，累计解压文本最大 100 MiB。
- 全包先校验，后在一个短事务中批量 Upsert。
- 任意错误整包回滚；重复上传覆盖旧内容。

### 13.4 任务与读取

课时和批量“AI 一下”只创建有序持久化任务，页面不等待外部调用完成。Worker 按课时内转写、摘要、出题顺序全局串行执行；ASR 使用独立单线程池，摘要和出题共用独立 LLM 单线程池，Worker 同步等待线程池调用完成以禁止任务并发。两个线程池定期打印存活状态和当前任务标识。失败任务由管理员手动重试。定时补全默认关闭，开启后只为缺失全文或摘要创建任务，不自动生成题目草稿。

`GET /api/v1/lessons/{lessonId}/study-content` 返回 `lessonId`、全文与摘要状态、可空的 `fullText`、可空的 `summaryMarkdown`、各自更新时间和总更新时间。两项内容都不存在时返回 RFC Problem Details，稳定错误码 `LESSON_STUDY_CONTENT_NOT_FOUND`。

---

## 14. 服务端 AI 边界

V1 服务端只允许 ASR 转写、LLM 摘要和 LLM 题目草稿三类课程内容生产调用。转写和摘要只能写入当前课时的 `full_text`、`summary_markdown`；AI 出题只能写入不可供学习端读取的草稿表。只有管理员明确发布后，确定性应用服务才能把已校验草稿写入正式题库。AI 不能读取或修改计划、欠债、可信进度、考试目标、答题记录、报表和用户偏好。

V1 仍不包含 MCP、AI Chat/SSE、智能体、联网搜索、AI 计划、AI 审判或其他 AI 业务写能力。Flutter 只读课程内容，不持有 ASR、LLM、OpenRouter 或 Emby 密钥。

---

## 15. Flutter 移动端架构

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

### 15.3 本地存储

V1 不引入 Drift 或本地 SQLite。

使用：

- `flutter_secure_storage`：Access/Refresh Token。
- `shared_preferences`：主题、验活偏好、设备 ID 等非敏感设置。

原因：

- 视频本身需要联网。
- 业务数据以服务端为准。
- 避免在 V1 提前实现复杂离线同步。

### 15.4 导航

底部四个 Tab：

```text
首页 | 学习 | 数据 | 我的
```

主要页面：

- 登录。
- 首次考试目标设置。
- 首页。
- 学习 Tab 作战日历：按月查看完成打勾和欠债标记，点选日期阅读该日作战单；默认先列出过去未完成作战单，再列出当天。
- 作战单编排与修改；仅当天未结算作战单可写，历史日只读。
- 课程详情；课程列表不再作为学习 Tab 根页，课时选择只出现在编排区。
- 视频播放。
- 课后答题。
- 首页右滑小工具菜单和专注计时。
- “我的”页面独立的模拟考试预置入口、考试倒计时和试卷照片上传。
- 欠债列表。
- 日报和周报。
- 学习偏好，包括验活开关、所有用户可用的进度百分比滑杆和日终时间。

### 15.5 视频页面

手机竖屏：

- 顶部视频。
- 可信进度条、当前时间、总时长、快退 10 秒、播放暂停、快进 10 秒和全屏按钮都覆盖在视频画面内。
- 点击画面显示控制层，播放约 3 秒后自动隐藏；控制层支持 Dynamic Type 和最小 44pt 点击区域。
- 全屏复用同一播放器 Controller 和观看会话，进入横屏沉浸模式，退出后恢复竖屏与系统 UI。
- 播放器下方仅在 `summaryStatus=READY` 且 `summaryMarkdown` 非空时展示 Markdown 摘要；没有摘要时整个区域隐藏。
- 验活使用不可绕过的模态框。

iPad 与 Android：

- iPad 复用 iOS 工程，根据可用宽度调整内容栏和播放器尺寸，不复制业务页面。
- Android 复用相同路由、Repository、Controller 和业务组件，仅在系统返回、权限、文件选择和播放器适配层处理平台差异。
- 平板横竖屏切换不得创建新的观看会话，也不得重置可信进度和验活状态。

App 生命周期：

- `inactive`、`paused`、`detached` 时暂停。
- 恢复后重新向服务端确认会话状态。
- 心跳连续 3 次失败时暂停并提示网络异常。
- 服务端拒绝进度时，播放器回退到服务端可信位置。

课程详情课时行显示未学习、可信进度百分比或已学习文字，并在摘要可用时显示小眼睛按钮。点击小眼睛按需读取本课时摘要并弹出底部面板；课程详情不提供加入作战单操作。

### 15.6 UI 原则

- V1 仅亮色主题。
- iPhone、iPad 和 Android 使用同一视觉语言，并遵循各平台的系统返回、权限与安全存储习惯。
- 系统字体，支持 Dynamic Type。
- 颜色不是唯一状态表达方式。
- 主要操作最小点击区域 44pt。
- 重要确认使用触觉反馈。
- 不为未来 Android 牺牲当前 iOS 体验。

### 15.7 服务端地址配置

登录页右上角提供未登录可访问的“服务器设置”，用于本地开发、局域网真机和自建部署切换服务端。

规则：

- 默认地址由 `API_BASE_URL` 编译参数提供，未传入时为 `http://127.0.0.1:18080`。
- 用户保存的地址存入 `SharedPreferences`，并优先于编译默认值。
- 地址必须是完整的 HTTP 或 HTTPS Origin，不允许用户名、密码、Query、Fragment 或 API 子路径。
- 保存前调用目标服务的 `/actuator/health`；只有返回 2xx 且状态为 `UP` 才允许切换。
- 健康检查不得携带 Bearer Token。
- 切换地址必须清除旧服务器的 Access Token 和 Refresh Token，并整体重建 ApiClient、认证控制器和所有 Repository。
- 登录页展示当前服务器主机与端口。
- HTTP 只用于本机或局域网开发，生产环境必须使用 HTTPS。
- 本功能只保存连接配置，不增加本地业务数据、离线同步或 Mock 服务。

### 15.8 已登录启动恢复

- App 有本地 Token 时，恢复登录最多等待 8 秒，不能无限显示启动转圈。
- 网络不可达或超时时保留 Token，进入“服务暂时不可用”页面，提供重新连接、修改服务器和退出登录。
- 只有服务端明确返回 401 且刷新失败时才清除 Token。
- App 回到前台可自动重试一次；同一前台周期不得无限重试。

---

## 16. 管理后台

使用 Spring Security Session、Thymeleaf、CSRF 和服务器渲染。

管理后台 Session 通过 Spring Session JDBC 持久化到现有 SQLite，七天无访问后失效；服务重启不得主动清除有效登录状态。App Bearer Token API 保持无状态。

路径：

```text
/ -> /admin/health
/admin -> /admin/health
/admin/login
/admin/users
/admin/emby
/admin/courses
/admin/courses/{id}/lessons
/admin/courses/{id}/study-content/import
/admin/content-jobs
/admin/content-jobs/{id}
/admin/lessons/{id}/questions
/admin/health
/admin/settings/integrations
```

权限：

- 仅 ADMIN。
- 管理后台与 App Token 认证分离。
- Cookie 使用 HttpOnly、Secure、SameSite=Lax。
- 页面轮询接口返回原始枚举值和中文显示值；浏览器只局部更新任务统计、表格、状态和阶段日志，认证失效时跳转登录页。
- 登录失败有基本限速。
- 首个管理员通过环境变量引导创建，首次启动后要求修改密码。
- 管理员可配置 Emby、ASR、LLM 和 OpenRouter 模型目录；API Key 字段支持显示和隐藏。
- 配置页面禁止缓存，保存操作使用 CSRF，保存成功后对新调用立即生效。
- 课程课时页可上传 ZIP 批量导入全文和摘要；先全包校验，再在一个事务中写入。
- 课程课时页提供单课时和批量内容生成按钮；内容任务页提供状态、日志、耗时和重试。

V1 后台功能以可用为主，不做复杂设计系统。

---

## 17. 安全要求

- 生产环境 HTTPS。
- 禁止在仓库提交任何密钥。
- `.env.example` 只放变量名。
- JWT Secret 至少 32 字节随机值。
- Refresh Token 数据库存哈希，不存明文。
- 密码使用 BCrypt strength 12。
- Emby API Key 只允许存在于服务端存储和 ADMIN 配置页面，不得进入 Flutter、业务 API、日志或错误响应。
- ASR、LLM 和 OpenRouter API Key 只允许存在于服务端存储和 ADMIN 配置页面，不得进入 Flutter、业务 API、任务日志或错误响应。
- 播放代理固定目标，禁止用户传任意 URL。
- HLS 路径重写需防目录穿越。
- 所有资源查询校验当前用户所有权。
- 日志不记录 Token、密码、API Key 和完整媒体 URL。
- 管理后台开启 CSRF。
- App 端不信任本地时间和本地完成状态。
- 切换服务端地址时必须先清除旧服务器 Token，且地址不得嵌入认证信息。

---

## 18. 错误处理

错误编码示例：

```text
AUTH_INVALID_CREDENTIALS
PLAN_ALREADY_LOCKED
PLAN_NOT_LOCKED
PLAN_ALREADY_CLOSED
PLAN_VERSION_CONFLICT
PLAN_ITEM_IMMUTABLE
DEBT_NOT_OPEN
VIDEO_NOT_COMPLETED
SEEK_NOT_ALLOWED
WATCH_SESSION_EXPIRED
WATCH_HEARTBEAT_REJECTED
ALIVE_CHECK_REQUIRED
QUIZ_LOCKED
EMBY_UNAVAILABLE
EMBY_PARENT_NOT_FOUND
EMBY_MEDIA_MAPPING_CONFLICT
MEDIA_NOT_AVAILABLE
LESSON_STUDY_CONTENT_NOT_FOUND
STUDY_CONTENT_IMPORT_INVALID
EMBY_AUDIO_UNAVAILABLE
ASR_NOT_CONFIGURED
ASR_REQUEST_FAILED
LLM_NOT_CONFIGURED
TRANSCRIPT_NOT_READY
SUMMARY_REQUEST_FAILED
MOCK_EXAM_PRESET_NOT_FOUND
MOCK_EXAM_ILLEGAL_TRANSITION
MOCK_EXAM_ATTACHMENT_INVALID
MOCK_EXAM_ATTACHMENT_LIMIT
```

客户端规则：

- 展示可操作提示，不直接显示堆栈。
- 网络错误支持重试。
- 对 `SEEK_NOT_ALLOWED`，播放器回到服务端位置。
- 对 `STUDY_CONTENT_IMPORT_INVALID`，管理后台显示具体 Emby Item ID 和可修复原因，不显示堆栈。

---

## 19. 可观测性与运维

必须：

- Spring Boot Actuator `/actuator/health`。
- 每个请求生成 `requestId`。
- 日志包含用户 ID、会话 ID、模块和耗时，不包含敏感内容。
- Emby 调用记录耗时和状态。
- 课程内容导入记录数量、耗时和结果，不记录正文。
- 内容生成记录任务 ID、阶段、模型名和耗时，不记录完整音频 URL、正文、Prompt 或上游响应。
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

课程全文和摘要已经在数据库备份内。

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
- 作战单完整快照保存、版本冲突、不可修改项目保护和修订审计。
- 模拟考试预置、倒计时、提前交卷、附件校验和完成状态。
- 欠债计算。
- 可信进度校验。
- 验活调度。
- 考试进度压力。
- 日报和审判模板。
- ZIP 结构解析和导入校验。
- 内容任务状态机、ASR NDJSON 拼接、上下文预算和摘要分段合并。

逻辑与协议测试：

- 领域状态机、策略和应用服务通过 Fake、Stub 或 Mock Repository 验证，不启动 Spring Boot 完整上下文。
- 不启动 SQLite、Flyway 或其他真实数据库，不测试具体 SQL、数据库约束、事务和迁移。
- Controller 与 Spring Security 使用不连接数据库的测试切片。
- Emby、ASR、LLM 和 OpenRouter Models API 使用 WireMock。
- Emby 协议测试覆盖媒体库列表、配置用户作用域、Movie/Episode/Video 混合类型、分页失败不返回部分结果和父节点不存在。
- 课程同步应用服务测试覆盖来源指纹映射、无指纹历史课时唯一兼容、歧义拒绝、重新绑定原子性和本地课时 ID 不变。
- ZIP 解析、全包校验和批量写入命令生成使用内存对象验证；数据库原子性转为代码评审和人工发布验证。
- Range 转发和 HLS 重写使用内存流或 WireMock，不连接业务数据库。

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
- 计时器。

Widget 测试：

- 首页。
- 作战单编排、完整保存和修改。
- 非作战单播放提醒。
- 模拟考试预置选择与试卷上传。
- 欠债展示。
- 验活弹窗。
- 题目页面。
- 四 Tab App Shell。
- 播放页无 AI 入口。
- 登录后可拖动学习伙伴；全屏播放时贴边隐藏；伙伴不提供对话。
- 播放器画面内控制层、横屏全屏和有条件摘要。
- 已登录启动服务不可达恢复页。
- 日报和周报日期选择器。

端到端逻辑测试：

- 登录。
- 编排、保存和修改今日作战单。
- 启动学习会话。
- 完成题目。
- 日终自动结算欠债、自由学习和开摆日。
- 读取已导入课程内容。

真实视频播放需在至少一台物理 iPhone、iPad 和 Android 设备做 smoke test。

### 21.3 完成门槛

每个任务提交前：

```bash
make format
make verify
```

必须通过：

- Java 编译和测试。
- Flutter format、analyze、test。
- OpenAPI 静态合同检查。
- 无 secret 扫描命中。
- 无禁用测试。
- 无未解释的 warning。

---

## 22. CI

建议 GitHub Actions：

### `server-verify`

- Ubuntu。
- Java 21。
- `./mvnw verify`，只运行不连接真实数据库的逻辑、协议和 Controller 测试。
- 上传测试报告。

### `mobile-verify`

- macOS。
- FVM 安装固定 Flutter。
- `flutter pub get`。
- `dart format --output=none --set-exit-if-changed .`
- `flutter analyze`。
- `flutter test`。
- 构建 iOS Simulator，禁止签名。
- 构建 Android APK。

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
  └─ /data/study.db
       ↓
     Emby
```

关键约束：

- 数据卷必须是宿主机本地磁盘。
- 服务实例数固定为 1。
- Emby 和后端之间优先内网。
- 媒体代理不设置过小的反向代理超时。

环境变量提供首次启动和未保存后台配置时的初始值：

```text
APP_BASE_URL
JWT_SECRET
PLAYBACK_TICKET_SECRET
ADMIN_BOOTSTRAP_USERNAME
ADMIN_BOOTSTRAP_PASSWORD
EMBY_BASE_URL
EMBY_API_KEY
EMBY_USER_ID
ASR_BASE_URL
ASR_API_KEY
ASR_MODEL
LLM_BASE_URL
LLM_API_KEY
LLM_MODEL
OPENROUTER_API_KEY
QUIZ_DEBT_ESTIMATE_SECONDS
DATA_DIR
BACKUP_DIR
```

管理员可通过 `/admin/settings/integrations` 将 Emby、ASR、LLM、OpenRouter 和自动补全配置保存到 SQLite。数据库中存在配置后，整份数据库配置优先于环境变量；SQLite 备份包含该配置和外部服务密钥。

---

## 24. 性能目标

在同时在线不超过 5 人的前提下：

- 普通业务接口 P95 小于 300ms，不包含外部服务。
- Dashboard P95 小于 500ms。
- 心跳处理 P95 小于 100ms。
- 单用户同时仅一个观看会话。
- 媒体代理不得整文件缓冲。
- 内容任务全局同时只执行一个；音频和外部响应必须流式处理，不将完整视频读入内存。
- 后端稳定运行 7 天无锁死和数据库损坏。
- 数据库出现 `SQLITE_BUSY` 时有 5 秒等待，并记录指标日志。

---

## 25. 未来平台保留

V1 同时面向 iPhone、iPad 和 Android，以下设计保持平台中立：

- 所有业务规则在服务端。
- API 不出现 `/ios`。
- 媒体播放票据不依赖 iOS。
- 使用 `/api/v1` 版本化。
- DTO 不包含 Flutter 类型。
- iPhone、iPad 和 Android 复用 Flutter 工程和服务端 API。
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
5. 用户在作战单编排区选择视频并保存今日作战单。
6. 用户可以再次进入“修改作战单”调整尚未开始的项目。
7. 用户播放视频。
8. 快进到未看区域被拒绝。
9. 触发并通过随机验活。
10. 视频完成。
11. 用户提交课后题。
12. 计划任务完成。
13. 日报显示学习和答题数据。

### 场景 B：日终结算与欠债

1. 用户保存包含两个任务的作战单。
2. 完成第一个，第二个只做一部分。
3. 到达用户日终时间。
4. 服务端将作战单结算为 `CLOSED_WITH_DEBT`。
5. 欠债记录保留原任务和剩余量。
6. 晚间审判显示未完成作战单和欠债。
7. 第二天计划页优先展示该欠债。
8. 完成还债任务后欠债变为 PAID。

### 场景 I：作战单编排、复习和模拟考试

1. 用户从“我的”页面独立的“模拟考试预置”菜单创建考试名称与时长预置。
2. 用户在作战单编排区选择未学课时、已学课时和考试预置；已加入今日作战单的项目在选择器中不可再点，未开始项目从作战单列表删除，课时严格按视频固有顺序排列并整体保存。
3. 已学课时显示为复习快捷入口，播放可自由拖动且不改变作战单完成率。
4. 第一次有效复习心跳生成一条审计事件，重复心跳不重复记录。
5. 用户修改作战单，删除一个未开始项目；日终不为已删除项目生成欠债。
6. 模拟考试进行中返回须二次确认，确认后立即停止倒计时并离开；保持屏幕常亮并提示不要切到后台。切后台或被系统回收后重新打开仍按服务端截止时间继续，到时进入等待上传。
7. 上传至少一张合法试卷照片后模拟考试完成，非法或越权文件被拒绝。

### 场景 J：无阻塞启动和报表导航

1. 已登录用户在服务端不可达时重新打开 App。
2. 8 秒内显示恢复页面而非无限转圈，Token 不被误删。
3. 用户可重试、修改服务器或退出登录。
4. 日报可通过日期选择器跨月跳转，周报可选择任意日期定位所在周。

### 场景 C：批量导入课程学习内容

1. 管理员进入一个课程的课时页。
2. 上传 manifest、全文和摘要结构正确的 ZIP。
3. 服务端按 Emby Item ID 精确匹配当前课程课时。
4. 全包校验成功后一次导入全部内容。
5. 重复上传后对应课时内容被覆盖，首次导入时间保留。
6. 上传缺少摘要、包含其他课程课时或非法路径的 ZIP 时整包不写入。

### 场景 D：读取课程学习内容

1. 登录用户请求已有课时内容的学习内容接口。
2. 响应返回全文与摘要各自状态、可空内容和更新时间。
3. 未导入内容的课时返回 `LESSON_STUDY_CONTENT_NOT_FOUND`。
4. Flutter Shell 只有“首页 / 学习 / 数据 / 我的”，播放器没有 AI 入口。学习伙伴可以拖动，但不是 AI 入口。

### 场景 E：恢复与备份

1. 运行在线备份。
2. 校验备份完整性。
3. 在独立目录恢复。
4. 启动服务。
5. 用户、课程、计划、进度、欠债和课程学习内容均存在。

### 场景 F：切换开发服务端

1. 用户在未登录状态打开服务器设置。
2. 非法或不可达地址不会覆盖当前配置。
3. 可达服务的健康检查返回 `UP`。
4. App 保存新地址并清除当前 Token。
5. App 无需进程重启即可重建全部网络依赖。
6. 登录页显示新服务器，用户可使用新服务器账号登录。

### 场景 G：后台配置外部服务

1. 管理员进入服务配置页。
2. 页面显示环境变量或数据库中的 Emby、ASR、LLM、OpenRouter 和自动补全配置。
3. 管理员修改配置并保存。
4. 配置无需重启即可供新调用使用。
5. 非管理员、缺少 CSRF 和非法 URL 的写入被拒绝。
6. Flutter、业务 API、日志和错误响应不包含密钥。

### 场景 H：自动转写、摘要与 AI 出题

1. 管理员对一个或多个长视频课时点击“AI 一下”。
2. 服务端按转写、摘要、出题顺序创建持久化工作流任务。
3. 服务端直接从 Emby 获取完整音频流，ASR 返回的 `text` 被保存为全文。
4. 服务端根据模型上下文对长全文递归分层，忠实生成摘要，再生成结构合法的题目草稿。
5. 管理员在课程草稿页选择多个课时，批量通过后正式题库追加题目，或批量驳回、删除未发布草稿。
6. 所有阶段均按课时顺序、全局串行执行。
7. 定时补全默认关闭；开启后只补缺失全文或摘要，不生成题目，不覆盖已有内容。
8. OpenRouter 模型目录刷新失败时仍可使用缓存模型或手动模型配置。

### 场景 K：Emby 媒体库重建与无缝重映射

1. 管理员从 Emby 媒体库列表选择一个包含 Movie、Episode 和 Video 的混合媒体库创建课程。
2. 服务端按配置用户权限分页同步全部视频，电影、剧集和普通视频均不遗漏。
3. 用户产生可信进度、计划、欠债、题目、全文和摘要数据。
4. 管理员在 Emby 删除该媒体库，并以相同媒体文件重新创建，父节点和视频 Item ID 发生变化。
5. 旧父节点同步返回稳定错误并保留本地快照，不把全部课时标记为不可用。
6. 管理员将原课程重新绑定到新媒体库；来源指纹或唯一历史元数据映射在一个事务中更新外部 Item ID。
7. 重新绑定后本地课时 ID、可信进度、计划、欠债、题目、全文和摘要保持不变，新播放使用新的 Emby Item ID。
8. 存在同名同长度等歧义时系统拒绝自动合并，管理员确认全部冲突后才能提交重新绑定。

### 场景 L：Emby 来源联想、批量建课与课程归档

1. 管理员在服务配置中绑定多个 Emby 媒体库，并分别选择剧集、电影或混合类型。
2. 管理员点击“选择 Emby 课程”打开弹窗，无需先搜索即可看到已绑定媒体库下符合类型的全部 Series 或 Movie，并可在弹窗内搜索过滤和勾选；必要时仍可通过兼容入口使用媒体库、Folder 或手工 Item ID。
3. 管理员选择一个来源执行“添加并同步”，系统使用来源名称创建课程并完整同步视频。
4. 管理员选择多个来源执行“批量添加并同步”，每个来源创建一门课程，页面逐项展示创建、恢复、跳过或同步失败结果。
5. 重复活动来源不会创建第二门课程；仍处于已归档但尚未物理删除状态的同来源课程恢复原课程身份。
6. 管理员归档课程时看到保留历史提示，确认后课程从 App 和活动后台列表隐藏，但课时及学习数据仍存在。
7. 管理员从已归档列表恢复课程，原课程 ID、课时 ID、可信进度、计划、欠债、题目和学习内容保持不变。
8. 管理员在已归档课程区域单个删除或勾选多门课程批量删除，二次确认展示课程、课时及各类关联记录数量；确认后课程完整关联数据图被物理删除且不可恢复。
9. 管理员重新添加相同 Emby 来源时得到新的课程与课时身份，只同步当前 Emby 视频，不带回已删除历史。

---

## 28. Definition of Done

V1 只有同时满足以下条件才完成：

- 所有 V1 验收场景通过。
- 后端和 Flutter 测试全部通过。
- 物理 iPhone、iPad 和 Android 设备完成关键流程验证，iPhone 连续使用 7 天无阻断性问题。
- Emby Key 未出现在 App 包、日志和 API 响应。
- 未看区间无法通过正常 UI 和直接 API 心跳绕过。
- 作战单正常日终会幂等生成欠债，无作战单且无学习活动时幂等生成开摆日结果。
- 服务端只包含受限的课时转写、摘要和题目草稿能力；Flutter 不包含聊天、智能体、MCP 或外部服务密钥。学习伙伴仅本地动画。
- 课程学习内容 ZIP 导入满足全包原子性，移动端接口只读。
- 内容任务持久化、全局串行，长文本不超过配置的模型上下文预算，临时音频可靠删除。
- AI 题目必须经过管理员审核或批量发布，不能直接进入学习端。
- 数据库可在线备份并成功恢复。
- 文档、环境变量和运行命令完整。
- 没有 PC Web、微服务、Redis、向量库等越界实现。
- 服务端地址切换经过健康检查，且不会向新服务器发送旧 Token。
- 管理员保存外部服务配置后，新请求无需重启即可使用最新配置。
- Emby 媒体库删除或重建不会静默丢失、复制或错误合并本地课时与可信学习历史。

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
11. 课程全文和摘要批量导入。
12. 课程学习内容只读接口。
13. 运维、备份、真机验收和 TestFlight。
14. Web 后台 Emby 运行时配置。
15. 课程自动转写、摘要、AI 出题、模型目录和内容任务后台。
16. 作战单编排、模拟考试预置与附件、复习审计、独立专注工具和自动日终结果。
17. 播放器全屏与摘要、启动容错、课时学习状态和报表日期选择器。
18. Emby 媒体库选择、混合视频分页同步和媒体库重建后的稳定课时重映射。

每个切片必须独立测试、独立提交，不允许先铺满空壳再统一补实现。

---

## 30. 参考基线

实现时以以下官方资料为技术事实来源：

- Flutter Supported Deployment Platforms。
- Flutter iOS Deployment。
- Flutter `video_player` package。
- Emby REST API、API Key Authentication、PlaybackInfo、Dynamic HLS 和 Playstate API。
- SQLite WAL 和 Isolation 文档。
- Spring Boot 4.1 System Requirements。
- springdoc-openapi Spring Boot 4 compatibility documentation。
