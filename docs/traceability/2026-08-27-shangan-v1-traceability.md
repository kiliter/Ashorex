# 上岸 V1 需求—实现—验收追踪矩阵

**日期：** 2026-08-31
**用途：** 防止 Codex 漏实现需求，或用未验收的实现冒充完成。

| ID | V1 需求 | 主要实现 Task | 关键自动化验证 | 最终验收 |
|---|---|---:|---|---|
| PLAT-001 | 只交付 iOS Flutter App | 4、10、19 | Flutter analyze/test、iOS integration test | 真机启动、登录、完整流程 |
| AUTH-001 | App JWT 登录、刷新、退出 | 3、4 | `AuthFlowIntegrationTest`、`auth_controller_test.dart` | Token 轮换且旧 Token 失效 |
| AUTH-002 | 管理后台 Session 与 CSRF | 3、16 | `AdminSecurityTest` | 非管理员不可进入后台 |
| EXAM-001 | 考试倒计时与目标完成日 | 6 | `ExamProgressCalculatorTest` | 固定日期计算结果正确 |
| EXAM-002 | 根据剩余课程计算进度压力 | 6 | `ExamProgressCalculatorTest` | 显示所需速度、实际速度和风险状态 |
| PLAN-001 | 创建、编辑和锁定每日计划 | 7 | `DailyPlanStateMachineTest` | 锁定后增删改被拒绝 |
| PLAN-002 | 日终自动关闭计划 | 7、12 | 日终调度集成测试 | 跨用户时区正确结算 |
| DEBT-001 | 未完成任务按组成部分生成欠债 | 7 | `DebtGenerationIntegrationTest` | VIDEO_WATCH、QUIZ、FOCUS 均正确记债 |
| DEBT-002 | 重复关账不产生重复欠债 | 7 | 幂等关账测试 | `source_plan_item_id + debt_type` 唯一 |
| ABANDON-001 | 开摆立即结束当日计划 | 7 | 状态机和事务集成测试 | 状态变为 `ABANDONED` |
| ABANDON-002 | 开摆剩余任务全部转欠债 | 7 | `DebtGenerationIntegrationTest` | 确认页金额与落库一致 |
| EMBY-001 | 绑定 Emby Series/Folder 并同步 | 5 | Emby WireMock 集成测试 | 新增剧集可同步成课程视频 |
| EMBY-002 | Emby Key 仅存在服务端 | 5、8、16 | 配置和响应安全测试 | App 流量中不出现 Key |
| PLAY-001 | 服务端签发短期播放票据 | 8 | `PlaybackTicketServiceTest` | 过期/越权票据被拒绝 |
| PLAY-002 | 支持 Range 与 HLS 代理 | 8 | `RangeProxyIntegrationTest`、`HlsManifestRewriteTest` | iPhone 可播放直放与转码源 |
| WATCH-001 | 禁止跳入未验证观看区间 | 9、10 | `WatchProgressPolicyTest`、播放器 Widget 测试 | 拖动只能到可信边界 |
| WATCH-002 | 心跳累计可信观看时间 | 9、10 | `WatchSessionIntegrationTest` | 暂停、后台、序号重复不累计 |
| WATCH-003 | 退出后从可信进度恢复 | 9、10 | `WatchSessionIntegrationTest` | 重启 App 后边界一致 |
| ALIVE-001 | 按监督等级随机验活 | 9、10 | `AliveCheckSchedulerTest` | 触发区间符合设置 |
| ALIVE-002 | 未响应验活停止累计 | 9、10 | 心跳/验活集成测试 | 超时后进度不再前进 |
| QUIZ-001 | 管理后台配置单选和判断题 | 11 | 后台 Controller 测试 | 题目可创建、排序、禁用 |
| QUIZ-002 | 视频完成后解锁答题 | 11 | `QuizUnlockIntegrationTest` | 未完成视频返回业务错误 |
| QUIZ-003 | 保存答案、得分、正确率并结清 QUIZ 欠债 | 11、12 | `QuizAttemptIntegrationTest` | 日报、周报和欠债状态一致 |
| FOCUS-001 | 番茄钟、练习和模拟考试计时 | 12 | `FocusSessionStateMachineTest` | 暂停、恢复、完成状态正确 |
| REPORT-001 | 每日数据汇总 | 12 | `DailyReportAggregationTest` | 学习、答题、欠债、验活一致 |
| REPORT-002 | 每日晚间审判 | 12 | 规则模板测试 | 完成/欠债/开摆文案符合规则 |
| REPORT-003 | 每周报表 | 12 | `WeeklyReportAggregationTest` | 七日聚合与原始数据一致 |
| CONTENT-001 | 按课程 ZIP 批量导入每集全文和 Markdown 摘要 | 19 | ZIP 解析单元测试、`LessonStudyContentImportIntegrationTest` | Emby Item ID 精确匹配且完整包导入成功 |
| CONTENT-002 | 导入全包原子，重复上传覆盖 | 19 | 回滚、重复覆盖与固定 Clock 集成测试 | 任意错误零写入，成功覆盖且时间字段正确 |
| CONTENT-003 | App 只读获取单集学习内容 | 19 | `LessonStudyContentApiIntegrationTest`、OpenAPI 契约测试 | 返回全文、摘要和更新时间，未导入错误码稳定 |
| CONTENT-004 | 迁移保留历史全文和全局摘要并删除 AI 数据 | 19 | V013 迁移集成测试 | 旧内容可读，聊天/转写/FTS/旧摘要表已删除 |
| ADMIN-001 | 用户、课程、题目、课程内容和运行状态管理 | 3、5、11、16、19 | MVC/安全集成测试 | 管理员完成全部配置流程 |
| ADMIN-002 | Web 后台配置 Emby 并立即生效 | 18、19 | `RuntimeIntegrationSettingsServiceTest`、`IntegrationSettingsAdminTest`、Emby 适配器刷新测试 | 保存后不重启，新请求使用最新配置 |
| OPS-001 | 健康检查、结构化日志 | 2、16 | Actuator/日志测试 | `/actuator/health` 正常 |
| OPS-002 | SQLite 在线备份与恢复 | 16 | 备份脚本测试、恢复演练 | 恢复后核心表计数一致 |
| SEC-001 | 外部密钥不泄露 | 3、5、8、16、19 | Secret 扫描与日志测试 | 客户端包、日志、API 均无密钥 |
| SEC-002 | 媒体代理防 SSRF | 8 | 代理目标白名单测试 | 非 Emby 主机被拒绝 |
| SEC-003 | Emby 密钥仅在 ADMIN 页面和服务端存储中可见 | 18、19 | ADMIN/CSRF/禁止缓存/响应泄露测试 | Flutter、业务 API、日志和错误响应无密钥 |
| SCOPE-001 | 服务端与 Flutter V1 不包含 AI、ASR、MCP、自动转写或聊天入口 | 19 | 依赖/路由/配置残留扫描、Flutter Widget 测试 | 四 Tab、播放器无 AI，服务端无相关接口和依赖 |
| DEV-001 | 登录前配置服务端地址并安全切换连接 | 17 | 地址配置单元测试、登录页 Widget 测试 | 健康检查通过后切换，旧 Token 被清除且无需重启 App |

## 追踪规则

- 一个需求没有对应测试，不得标记为完成。
- 一个测试通过但最终验收失败，需求仍视为未完成。
- 新增需求必须先新增 ID、设计说明、Task 和验收项，再允许实现。
- 删除或弱化测试需要在 PR 中说明对应需求为何变化；否则拒绝合并。
