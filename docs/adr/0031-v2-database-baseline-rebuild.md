# ADR-0031：V2 重建数据库 baseline，放弃 V1 迁移历史与数据

- 状态：提议（待人工批准）
- 日期：2026-09-07
- 相关：前置于 ADR-0025 ~ ADR-0030 的落地

## 背景

V1 累积了 `V001` ~ `V029` 共 29 个 Flyway 迁移，其中包含大量 V2 将要删除的域：`watch_sessions`、`video_progress`、`alive_checks`、`learning_debts`、`debt_repayments`、`plan_abandonments`、`questions`、`question_options`、`quiz_attempts`、`quiz_answers`、`quiz_generation_*`、`daily_plans`、`daily_plan_items`、`daily_plan_revisions`、`daily_day_outcomes`、`mock_exam_presets`、`mock_exam_sessions`、`mock_exam_attachments`、`lesson_review_events`、`transcription_jobs`、`transcript_segments`(+FTS)、`video_section_summaries`、`lesson_study_contents`、`content_generation_jobs`、`content_generation_job_logs`、`ai_conversations`、`ai_messages`、`exam_goal_courses`。

如果坚持 append-only，V2 需要写一批只做 `DROP TABLE` 与表重命名的迁移，并且要在 SQLite 上重建 `courses`、`media_items`、`exam_goals` 的结构（SQLite 的 `ALTER TABLE` 能力有限，通常要走「建新表 + 拷数据 + 删旧表 + 改名」）。结果是十几个迁移文件、几百行只为拆掉旧结构的 SQL，并且新库的 schema 需要读 30 个文件才能看懂。

用户明确表示：不需要考虑迁移方案，支持数据库的完全重构；历史需求与留存材料都可以删除。

## 决策

- 删除 `apps/server/src/main/resources/db/migration/` 下 `V001` ~ `V029` 全部文件。
- 新建单一迁移 `V001__shangan_v2_baseline.sql`，一次性定义 V2 全部表、索引、约束与初始数据（全局催办策略默认行、运行配置单行）。
- **不提供任何 V1 → V2 数据迁移脚本**。V1 的用户、课程绑定、学习进度、欠债、答题、报表数据一律不迁移。
- 部署前置动作：备份现有 `study.db` 并归档留存，然后使用空数据目录启动 V2，管理员重新创建账号、重新绑定 Emby 课程、重新同步。
- 本 baseline 发布后立即重新进入 append-only 约束：后续任何结构变更必须新增 `V002`、`V003`……不得编辑已发布的 baseline。

## 前置条件（必须在实施 Task 中显式验证）

1. 现有 `study.db` 已完成 `.backup` 且 `PRAGMA integrity_check` 通过，备份文件在仓库外归档。
2. 使用**独立数据库副本**验证 baseline 可执行，不得在真实数据库上试跑迁移。
3. 真实 ApplicationContext 启动 Smoke Test 通过且健康端点 `UP`。

## 后果

正面：

- 新 schema 在一个文件内可读完，是 V2 的结构事实来源。
- 不需要为「删掉即将不存在的域」编写和测试十几个迁移。
- 避免 SQLite 表重建迁移的历史包袱与潜在失败点。

负面与代价：

- **V1 生产数据全部丢失**，这是不可逆的。仅在用户明确批准的前提下成立，且必须先归档备份。
- 若将来发现某些 V1 历史数据仍有价值（例如累计学习时长），只能从归档备份里手工捞取，没有自动路径。
- 违反了「迁移在发布后 append-only」的一般约束。本 ADR 是对该约束的一次性显式豁免，范围限定为「V2 baseline 重建」，不构成后续可以重写迁移的先例。

## 替代方案

1. **写 `V030` ~ `V04x` 逐步拆除旧域**。否决：十几个纯拆除迁移、大量 SQLite 表重建、schema 可读性差，且 V1 数据本身不需要保留。
2. **保留旧表不管，只新增 V2 表**。否决：直接违反「不留垃圾数据」的要求，且旧外键会阻碍课程与用户的级联删除。
3. **写 V1 → V2 数据迁移脚本**。否决：模型语义变化过大（欠债、答题、可信进度都没有对应概念），映射本身就是猜测，用户也明确表示不需要。
