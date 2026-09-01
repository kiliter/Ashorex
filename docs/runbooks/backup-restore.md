# 上岸 V1 备份与恢复手册

本文面向单进程、单 SQLite 实例的 V1 部署。数据库必须位于服务器本地磁盘的 `/data/study.db`；不要把 SQLite 主库放在 NAS 或网络文件系统上。

## 备份策略

- 每日使用 SQLite `.backup` 生成在线一致性备份，不能直接复制 WAL 模式下的 `study.db`。
- 每个备份必须执行 `PRAGMA integrity_check;`，输出必须精确为 `ok`。
- 保留最近 7 个日备份和最近 4 个周备份。
- 备份目录应由宿主机或独立存储系统继续做异机复制；容器卷本身不是异地灾备。

建议用宿主机定时任务每天 UTC 18:00（北京时间次日 02:00）执行：

```bash
docker compose -f infra/compose.yml exec -T server \
  env DATA_DIR=/data BACKUP_DIR=/backup /app/infra/scripts/backup.sh
```

当前镜像只打包服务端 JAR，生产部署可将 `infra/scripts` 只读挂载到 `/app/infra/scripts`，或在宿主机安装 `sqlite3` 后对已挂载的数据卷执行：

```bash
DATA_DIR=/srv/shangan/data BACKUP_DIR=/srv/shangan/backup \
  infra/scripts/backup.sh
```

成功日志只包含本地备份路径，不包含密钥或远端地址。失败时立即告警，不要删除上一份可用备份。

## 恢复前检查

1. 记录事故时间、当前应用版本和待恢复备份文件。
2. 停止服务端，确认没有 Java 进程继续访问该数据库。
3. 校验备份：

   ```bash
   sqlite3 /srv/shangan/backup/study-YYYYMMDD-HHMMSS.db "PRAGMA integrity_check;"
   ```

4. 输出必须精确为 `ok`。否则停止恢复并选择更早的可用备份。

## 执行恢复

服务停止后显式设置确认标志：

```bash
SERVICE_STOPPED=1 \
DATA_DIR=/srv/shangan/data \
infra/scripts/restore.sh /srv/shangan/backup/study-YYYYMMDD-HHMMSS.db
```

脚本会：

1. 再次校验输入备份。
2. 将当前 `study.db`、`study.db-wal` 和 `study.db-shm` 归档到 `pre-restore-时间戳/`。
3. 用 SQLite `.restore` 生成新主库。
4. 再次执行完整性校验后原子替换 `/data/study.db`。

不要在服务运行时设置 `SERVICE_STOPPED=1`。恢复脚本不会替你停止进程。

## 恢复后验收

先在只监听本机的环境启动服务，然后依次检查：

```bash
curl --fail http://127.0.0.1:18080/actuator/health
sqlite3 /srv/shangan/data/study.db \
  "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;"
sqlite3 /srv/shangan/data/study.db "PRAGMA integrity_check;"
```

登录管理后台确认用户、课程、计划、可信进度、欠债和课程学习内容仍存在，再开放 Caddy 流量。若发现错误，立即停止服务，将归档目录中的原数据库恢复回原位，并保留现场文件用于排查。

## 自动化演练

仓库 Smoke Test 会创建临时 WAL 数据库，在另一个连接保持打开时执行在线备份，再恢复到独立目录，并断言 Schema 版本及用户、课程、计划、进度、欠债、课程学习内容代表性行数：

```bash
infra/scripts/backup_restore_smoke_test.sh
```

该脚本只操作 `mktemp` 创建的目录，结束后自动清理。
