# ADR-0009：管理后台 Session 持久化到 SQLite

- 状态：已接受
- 日期：2026-08-31

## 背景

管理后台已经使用 Spring Security 表单登录、HttpSession 与 CSRF，但默认内存 Session 会在服务进程重启后全部丢失。上岸 V1 只有一个服务进程，主数据库固定使用本地 SQLite，管理后台同时在线人数少于 5 人。

## 决策

使用 Spring Session JDBC 将管理后台 Session 和认证上下文写入现有 SQLite：

- Session 表由 Flyway 追加迁移创建，Spring Session 禁止自行初始化数据库结构。
- Session 七天无访问后失效，正常服务重启不删除未过期 Session。
- 管理后台继续使用 HttpOnly、SameSite=Lax Cookie 和 CSRF；生产 HTTPS 部署强制启用 Secure，本机或局域网 HTTP 开发允许显式关闭 Secure。
- App `/api/v1/**` 继续使用无状态 Bearer Token，不读取或创建业务 Session。
- Session 属性只保存 Spring Security 认证上下文，不保存密码、API Key 或 JWT Secret。

## 备选方案

### 继续使用内存 Session

实现最少，但服务每次重启都要求管理员重新登录，不符合本地持续运维需求。

### 引入 Redis

能够持久化 Session，但会增加一个独立基础设施组件，与单实例、少量用户和 SQLite 基线不匹配。

### 使用长期认证 Cookie

需要额外设计令牌轮换、吊销和泄露处置，复杂度高于当前需求；因此不使用自定义长期明文认证 Cookie。

## 影响

- 服务重启后，只要浏览器仍持有有效 Cookie 且 Session 未过期，管理后台保持登录。
- SQLite 备份会同时包含有效管理后台 Session；恢复后仍由过期时间控制是否可用。
- 管理员登出会删除对应 Session 及属性记录。
