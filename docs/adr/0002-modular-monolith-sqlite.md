# ADR-0002：采用 Spring Boot 模块化单体与 SQLite

- 状态：已接受
- 日期：2026-08-27

## 背景（Context）

V1 同时在线用户少于 5 人，核心需求是可信地保存计划、进度、欠债、答题、报表、转写和 AI 会话。团队需要简单的部署、事务和备份模型，而不是为尚不存在的规模预付分布式系统复杂度。

## 决策（Decision）

服务端使用 Java 21 与 Spring Boot 模块化单体，单进程、单实例运行。代码按 `identity`、`exam`、`catalog`、`planning`、`debt`、`learning`、`quiz`、`focus`、`reporting`、`ai`、`media.emby` 和 `admin` 等业务功能分包。

业务数据库使用本机磁盘上的 SQLite，开启 WAL、外键、5 秒忙等待和 `synchronous=NORMAL`，Hikari 最大连接数为 4。数据库迁移使用 Flyway。应用服务持有事务，Repository 负责持久化，Controller 不直接访问数据库。

## 后果（Consequences）

- 计划关闭与欠债生成等强一致操作可在单个本地事务内完成。
- 部署、恢复和问题定位更简单，适合当前并发规模。
- 服务只能运行一个实例，SQLite 文件不能位于 NFS 等共享磁盘。
- 需要控制写事务长度，并监控 `SQLITE_BUSY` 和 WAL 大小。
- 当出现多实例、高可用或持续写竞争需求时，再评估迁移 PostgreSQL。

## 被否决的方案（Rejected Alternatives）

- 微服务：增加网络事务、部署和可观测性成本，当前没有规模收益。
- PostgreSQL 作为 V1 默认数据库：运维成本高于当前需求，且 SQLite 已满足规模与事务要求。
- Redis 或 Kafka：V1 没有需要独立缓存或消息基础设施解决的问题。
- 同时维护 SQLite 与 PostgreSQL 双兼容：会扩大迁移、测试和 SQL 维护范围。
