# 服务端自动升级与恢复

## 首次接入

适用 Linux Docker Engine + Docker Compose Plugin，默认 server/updater 均使用 host 网络，不映射端口。首次手动更新仓库到包含 ADR-0048 的版本，保留原 `.env.deploy`，执行 `./infra/scripts/deploy.sh deploy`。脚本读取 Docker socket GID；直接使用 Compose 时自行设置 `DOCKER_GID`。旧版镜像尚无维护协议，不能依靠旧 App 或后台自动完成首次接入。

同一镜像两个角色，server 不挂 Docker socket；updater 以 UID 10001 + socket 附加组运行，单独挂 socket 和 `shangan_update-state` 卷，无外部端口。socket 可控制整个宿主机，只授予可信 updater。不要删除原有 `shangan_study-data`、`shangan_study-backup`。升级器本身不自动更新，建议 `.env.deploy` 的 `SHANGAN_UPDATER_IMAGE` 固定为已验证版本。

首次安装完成，后台「版本与升级」应显示实际版本与升级器就绪。支持检查更新、预下载和确认升级。自动升级默认关闭，开启后按保存的 IANA 时区、HH:mm 每日一次；当天错过时间可补查，失败版本不定时重试。手动再次升级代表管理员明确重试。开发版本 dev 不无人值守升级。

## 网络与 Fake-IP

updater 使用 host 网络；`UPDATER_HTTP_PROXY`、`UPDATER_HTTPS_PROXY`、`UPDATER_NO_PROXY` 可在 `.env.deploy` 配置。版本源为固定 GitHub 仓库。镜像拉取通过 Docker daemon 完成，其代理/DNS 必须在宿主机单独配置；清单可读不代表 GHCR 可拉取。两项检查均在停止业务之前执行，失败不改变当前服务。

## 发布

正式 `vX.Y.Z` 标签触发服务端全量 CI、Docker Smoke、镜像推送和 Release 产物。`server-update.json` 最后公布，包含版本、提交、固定 digest、协议号、自动资格及中文说明。修改 `infra/updater/release-policy.json` 决定本版本是否允许无人值守；默认 false。所有自动版本必须兼容旧 App、当前部署环境和协议 1。JRE、数据卷、启动配置不兼容的版本不得自动放行。

后台只接受 CHECK、DOWNLOAD、APPLY 和开关/时间/时区，不接受任意镜像、URL 或命令。升级器从现有 server inspect 继承环境、卷、host 网络，启动命令及构建信息跟随新镜像。不要在手动 Compose up 中强行覆盖镜像；deploy.sh deploy 会保持已有协议容器的实际镜像，update 提交安全升级任务。

## 升级阶段与数据保护

拉取校验 → 写前日志 → 维护标志 → 停旧服务 → 独立容器备份 SQLite/附件并校验 → 保留旧容器、创建新容器 → 健康与实际版本验收 → COMMITTED → 开放业务 → 清理旧容器。数据卷保持原名，升级快照在备份卷 `upgrades/任务 UUID` 下，不参与每日保留清理。管理员确认稳定后再手动删除不需要的快照。

维护期间所有业务请求（包括后台 Session）返回 503、SERVER_UPGRADING、Retry-After: 30；健康与构建探测仍可读，自动催办及系统备份告警暂停。后台保留最后结果并持续重连。旧 App 按网络失败保留进度队列，恢复后重试，不强制客户端升级。新服务正常迁移及启动过程中会修改数据库，但验收前不开放业务，失败恢复这些修改。

## 失败恢复

拉取失败旧服务继续运行；备份失败重新启动旧容器；新版本失败先停新容器，恢复完整快照，再启动旧容器。updater 中断后重启，依 operation.json 接续恢复。COMMITTED/RECOVERED 是不可回退的提交点：此后只解除维护、清理，不再次还原数据。NEEDS_ATTENTION 保留维护与原容器，停止自动尝试。

人工处理前先停止 updater，保存 update-state、当前数据卷和备份卷；operation.json 包含部署环境凭据，禁止输出到工单或公共日志。根据记录确认旧容器、快照和阶段；停止所有 server 及升级辅助容器，再按 backup-restore.md 使用对应快照和附件恢复，启动旧镜像，验证健康和数据后才能移除 maintenance。不要直接删除 operation.json 或维护标志来跳过恢复。无法确认阶段时保留现场。

业务开放后出现问题，不允许自动恢复升级前快照，因为会丢弃新增学习记录。先备份当前数据，确认迁移向后兼容时才能仅回退镜像，否则发布修复版或经人工确认恢复。第一版无后台一键数据库回退按钮。

## 验证边界

本地只运行新增窄测试；CI 使用真实容器验证 ApplicationContext、健康、新镜像切换和故障恢复。Smoke 不测试具体 SQL 或数据库约束。上线前仍需在实际 Linux 主机验证 socket GID、反向代理、Fake-IP/代理和备份空间；CI 网络成功不能证明部署主机可达 GitHub/GHCR。
