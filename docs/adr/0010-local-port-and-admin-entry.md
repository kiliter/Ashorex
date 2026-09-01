# ADR-0010：统一本地端口与管理后台入口

- 状态：已接受
- 日期：2026-09-01

## 背景（Context）

本地服务原先默认监听 `8080`，容易与其他开发服务冲突；浏览器直接访问 `/` 或 `/admin` 时也会因为没有 Controller 映射而显示 Spring Boot Error Page，造成服务未启动的误判。

## 决策（Decision）

- Spring Boot、本地 Flutter、容器健康检查和开发文档统一使用 `18080`。
- 部署环境仍可通过 `SERVER_PORT` 覆盖监听端口。
- `GET /` 和 `GET /admin` 只做服务端重定向，统一进入 `/admin/health`。
- 未登录访问 `/admin/health` 时继续由现有 Spring Security 跳转 `/admin/login`，不绕过 ADMIN Session、CSRF 或权限检查。

## 后果（Consequences）

- 本地入口固定为 `http://127.0.0.1:18080`。
- 旧的客户端本地保存地址需要在服务器设置中切换一次。
- 根路径不新增业务页面，也不改变 `/api/v1` 或 OpenAPI 业务契约。
