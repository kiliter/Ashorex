# ADR-0052：本机诊断日志与手动上报

状态：已批准（2026-09-10，用户确认：本地文件 + 我的/关于/上报日志 + 管理后台查看；同日补充确认删除单份）。

## 决策

1. App 在本机写入滚动诊断日志，供播放跳片尾及其他缺陷事后分析。不自动上报，不采集全量网络正文。
2. 入口在「我的 → 应用信息 → 关于 → 上报日志」。用户复现后手动上传当前日志与上一份轮转备份。
3. 服务端按用户保存上传副本，管理后台可列表、查看正文，并在确认后删除单份台账与磁盘文件。不做检索集群、崩溃聚合或实时推送。
4. 日志必须脱敏：禁止写入 Authorization、Cookie、Bearer、API Key、SendKey、带查询串的完整 URL、Emby 原始路径、口令。写入前与落盘前各脱敏一次。
5. 本机单文件上限 2 MiB，写满轮转为 `.1` 备份；上传合并当前文件与 `.1`，服务端单份上限 4 MiB。每用户最多保留 30 份上传，超出删除最旧文件与台账行。
6. 彻底删除用户时级联清理 `diagnostic_log_uploads` 行与磁盘文件。

这不是通用崩溃平台，也不替代「中途 ended 不得按片尾完成」的业务修复。

## 本机记录范围

日志写详细，但禁止按播放进度每帧刷盘。至少包含：

| 类别 | 记录内容 | 不记录 |
|---|---|---|
| boot | 启动、平台、播放内核 | 服务端完整地址中的查询串 |
| lifecycle | resumed / paused / inactive / detached | — |
| nav | 路由变化 | 查询串中的 Token |
| auth | 登录成功/失败、恢复会话、退出；只记用户名与错误码 | 密码、Token |
| api | 写操作与全部 4xx/5xx：方法、路径、状态、errorCode、耗时 | 请求/响应正文、Authorization、Cookie |
| player | 打开/播放/暂停/ended/error/seek/上报（todoId、位置、时长、ended、playing、eventType、client 结果） | 流地址、请求头、Range |
| focus | 专注动作与结果状态 | — |
| nag | 待回应催办出现、提交回应结果 | 原因正文可记长度，不强制记全文 |
| heartbeat | 失败与恢复 | 成功心跳不逐次落盘 |
| flutter / zone | FlutterError、未捕获异常与堆栈（截断） | — |

行格式：`ISO-8601 UTC  LEVEL  [category] message key=value...`。

## 兼容性

- 新增 `POST /api/v1/diagnostics/logs`（multipart `file`，可选 `appVersion` / `platform`）与 `/admin/api/diagnostic-logs` 列表、正文、删除接口。
- 旧版 App 忽略关于页与该接口，行为不变。
- 追加迁移 `V012__diagnostic_log_uploads.sql`。
- 存储目录 `${DIAGNOSTICS_DIR:${DATA_DIR:./data}/diagnostics}`，文件名由服务端生成。

## 验收

- 脱敏去掉 Token、口令与查询串；空文件或超限拒绝。
- 关于页无日志时提示，有文件可上传成功。
- 后台能按时间看到用户、版本、平台与日志正文。
- 后台确认删除后台账与磁盘文件均不存在；删除不存在的记录返回 `DIAGNOSTIC_LOG_NOT_FOUND`。
- 用户彻底删除后磁盘与台账均无残留。
