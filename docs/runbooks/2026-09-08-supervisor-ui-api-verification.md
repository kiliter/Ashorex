# 督学端 UI 修复与接口联调记录

日期：2026-09-08。范围：V2 T15、T31，原型 9-2～9-6。

## 修复内容

- 学员、提醒、报告及学员详情恢复顶部安全区，避开刘海与状态栏。
- 学员卡按钮恢复原型的 1:1.2 宽度比例（flex 5:6），避免「查看详情」竖排；相关按钮最小高度为 44pt。
- 报告与详情的催办入口遵循 `canNag`；直接进入详情也会加载学员权限。
- 提醒接口增加 `sentByMe`，按实际发起人判定「我发的」，不再把管理员或其他督学的催办算入本人记录。
- 日／周／月报告催办次数按 `StatsView.start/end` 对应的学员本地日期聚合，避免最近 100 条的截断和跨区间混算。
- 提醒和报告下拉刷新等待请求完成后结束。

## 接口对接核对

| 接口 | 客户端仓库方法 | 本地真实验证 |
|---|---|---|
| GET `/api/v1/supervisor/learners` | `loadLearners` | 返回已绑定学员、完成数、在线状态和 canNag |
| GET `/api/v1/supervisor/learners/{learnerId}` | `loadLearner` | 返回 Todo 只读视图、统计、删除原因；未绑定返回 403 |
| GET `/api/v1/supervisor/feed` | `loadFeed` | 催办、回应原因、删除台账回显，sentByMe 正确 |
| GET `/api/v1/supervisor/report` | `loadReports` | DAY、WEEK、MONTH 区间及完成数、催办数、回应数正确；无数据的历史月为零 |
| POST `/api/v1/supervisor/learners/{learnerId}/nag` | `nagLearner` | 文案、渠道、是否要求原因透传；学员侧可读取并回应；关闭 canNag 后返回 403 |

## 验证边界

- 真实 HTTP 验证使用独立临时 SQLite 数据库和测试账号，不改写用户原有学习数据，也不发送 Server 酱外部推送。
- 使用真实响应额外验证 Flutter DTO 解析并渲染四页预览；该预览为 Widget 渲染，不等同于物理设备验证。
- 布局回归覆盖 390pt、320pt 及 59pt 顶部安全区；报告切换、我发的筛选、催办提交、无权限入口有对应窄测试。
- 服务端窄测试不启动数据库，覆盖视图口径与督学权限；运行时 OpenAPI 与冻结合同结构一致。
- GitHub CI 全量验证、物理设备与外部代理链路尚未执行。更新后需要重启后端并重新运行 App。
