<div align="center">

# 上岸 · Ashorex

**把考试目标、今日待办、真实学习时长与真人督学连成一个闭环。**

[![移动端验证](https://github.com/kiliter/Ashorex/actions/workflows/ios-verify.yml/badge.svg)](https://github.com/kiliter/Ashorex/actions/workflows/ios-verify.yml)
[![服务端验证](https://github.com/kiliter/Ashorex/actions/workflows/server-verify.yml/badge.svg)](https://github.com/kiliter/Ashorex/actions/workflows/server-verify.yml)
![Flutter 3.44.7](https://img.shields.io/badge/Flutter-3.44.7-02569B?logo=flutter&logoColor=white)
![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![iOS 16+](https://img.shields.io/badge/iOS-16%2B-111111?logo=apple&logoColor=white)
![Android API 24+](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)

[产品预览](#产品预览) · [核心能力](#核心能力) · [快速开始](#快速开始) · [构建产物](#github-actions-构建产物) · [项目文档](#项目文档)

</div>

> [!IMPORTANT]
> 上岸当前处于 V2.0.0 开发与真机验收阶段，面向自托管和小规模使用场景。V2 是一次产品模型重构：以「Todo 中心 + 真人督学」取代 V1 的可信播放、学习欠债与答题体系。不提供课程售卖、社交、AI Chat 或任何 AI 内容生产。

## 产品预览

### 移动学习端

<p align="center">
  <img src="docs/assets/readme/mobile-showcase.jpg" width="100%" alt="上岸移动端核心页面：作战单编排、首页、课程详情和学习日报">
</p>

<p align="center"><sub>从左到右：作战单编排、今日首页、课程可信进度与学习日报。</sub></p>

### 内部管理后台

<p align="center">
  <img src="docs/assets/readme/admin-content-jobs.jpg" width="49%" alt="上岸管理后台内容任务列表">
  <img src="docs/assets/readme/admin-courses.jpg" width="49%" alt="上岸管理后台课程管理页面">
</p>

<p align="center"><sub>管理后台负责课程同步、内容任务、题目草稿与运行配置；学习端不接触外部服务密钥。</sub></p>

## 它解决什么

很多学习工具只记录“打开过”或“播放过”，却无法回答三个更重要的问题：今天承诺了什么、实际做完了多少、没做完的部分去哪了。

上岸围绕一条明确的链路工作：

```text
考试目标倒计时看板
  → 今日 Todo（课程 / 专注计时 / 待办事项）
  → 执行（看视频 / 倒计时专注 / 手动勾选）
  → 进度与时长上报
  → 附件与备注回填
  → 日 / 周 / 月统计
  → App 心跳与在线判定
  → 服务端扫描未完成 → 催办（客户端全屏 / Server 酱）
  → 督学人查看与一键督学
```

监督压力来自**真人督学 + 催办留痕**，不来自自动惩罚或播放校验。

## 核心能力

| 能力 | 说明 |
|---|---|
| 今日 Todo | 三种类型：课程、专注计时、待办事项。可随时增删改，删除必须填写原因并留痕。 |
| 课程目标进度 | 长视频只想看一部分时，可设定「看到 30% 即算完成」；达标由服务端裁决。 |
| 专注计时 | 自定义名称与倒计时，倒计时结束判定完成，提前放弃记为未完成但保留已专注时长。 |
| 待办事项 | 纯文本任务，自行勾选，可要求上传完成凭证。 |
| 附件与备注 | 全类型共享，备注支持一键标签（已掌握 / 需重看 / 有疑问…）并进入统计维度。 |
| 日 / 周 / 月视图 | 首页可切换三种视图；历史未完成可顺延、补记完成或删除。 |
| 未完成汇总 | 跨日期聚合历史未完成项，按逾期天数分组，支持批量顺延。 |
| 学习数据 | 观看时长、专注时长与完成率；课程 / 人物 / 流派排行；月度热力图。 |
| 心跳与催办 | App 定时上报心跳，服务端扫描未完成并按可调阈值催办；在线走全屏弹框，离线走 Server 酱。 |
| 督学端 | App 内切换角色，查看学员学习情况与删除原因，一键督学。 |
| 内部管理后台 | 催办策略、在线监控、Emby 同步与失联迁移、督学关系、归档区与级联删除、运行配置。 |

## 系统架构

```text
┌──────────────────────────────────┐
│ Flutter Mobile App               │
│ iPhone / iPad / Android          │
│ 首页 · 学习 · 数据 · 我的         │
│ 督学端（App 内角色切换）          │
└───────────────┬──────────────────┘
                │ HTTPS / REST
                ▼
┌──────────────────────────────────┐
│ Spring Boot 模块化单体            │
│ Identity · Supervision · Goal    │
│ Catalog · Todo · Presence · Nag  │
│ Stats · Archive · Emby · Admin   │
└───────┬────────────┬─────────────┘
        │            │
        ▼            ├──────────────► Emby
     SQLite          └──────────────► Server 酱
```

- 业务真相保存在服务端，Flutter 页面不直接调用 Dio，而是通过 `ShanganRepository` 访问 API。
- 服务端采用单实例、模块化单体和本机 SQLite WAL，目标规模为少于 5 人同时在线。
- Emby 与 Server 酱密钥只存在于服务端环境或管理后台配置中，不下发给 App。
- 课程库的筛选维度（流派 / 标签 / 人物 / 年份）全部来自 Emby 元数据，本项目不维护这些主数据。

## 技术栈

| 区域 | 技术 |
|---|---|
| 移动端 | Flutter 3.44.7、Dart 3.12、Riverpod、go_router、Dio、video_player |
| 服务端 | Java 21、Spring Boot 4.1.1、Spring MVC、Virtual Threads、JdbcClient |
| 数据与迁移 | SQLite WAL、Flyway |
| 管理后台 | Vue 3 SPA（Vite、TypeScript、Pinia、Vue Router）、Spring Security Session + CSRF |
| 媒体与推送 | Emby、Server 酱 |
| 交付 | GitHub Actions、Docker、Caddy、FVM、Maven Wrapper |

## 快速开始

### 环境要求

- macOS 与 Xcode：运行 iPhone / iPad 模拟器时需要。
- Flutter `3.44.7`：通过 [FVM](https://fvm.app/) 锁定。
- Java `21`：仓库提供 `.sdkmanrc` 与 Maven Wrapper。
- Android Studio / Android SDK：构建或调试 Android 时需要。
- Docker 与 Docker Compose：仅容器部署时需要。

### 本地联调

```bash
git clone https://github.com/kiliter/Ashorex.git
cd Ashorex

# 首次准备 Flutter SDK 与依赖。
cd apps/ios
fvm install
fvm flutter pub get
cd ../..

# 默认启动 Spring Boot、iPhone 模拟器和 Flutter，服务端监听 18080。
./run.sh
```

首次运行会自动从 `.env.local.example` 复制出 `.env.local`（已被 Git 忽略），默认值为：

```dotenv
SERVER_ADDRESS=0.0.0.0        # 同时接受本机与局域网访问，方便真机连本机后端
SERVER_PORT=18080
ADMIN_BOOTSTRAP_USERNAME=admin
ADMIN_BOOTSTRAP_PASSWORD=123456
SESSION_COOKIE_SECURE=false   # 本机 HTTP 调试必须为 false，否则后台无法登录
DATA_DIR=./data               # 相对路径以仓库根为基准，启动时自动创建
```

脚本与 IDE 共用仓库根目录的 `.env.local`（含 `JWT_SECRET`、日志、静态资源和数据目录配置）。首次执行 `./run.sh server` 会生成 JWT 并写回该文件；已有 `.run/jwt-secret` 会被复用。

IntelliJ IDEA 启动配置：选择 Java 21 和 `com.shangan.ShanganApplication`，将 **Working directory** 设为仓库根目录，在 **Environment variables** 的环境文件设置中选择根目录 `.env.local`（若当前版本不支持，使用 EnvFile 插件）。首次启动前确保根目录 `data` 存在。Spring Boot 不会自行读取 dotenv 文件，必须启用 IDE 的环境文件加载。不要将含密钥的配置复制到可提交的运行配置中。

日志使用原生变量 `LOGGING_LEVEL_ROOT` 和 `LOGGING_LEVEL_COM_SHANGAN`；修改 `.env.local` 后，无论脚本还是 IDE 都需重启。

管理员只在数据库还没有任何管理员时创建；改过密码后 `.env.local` 中的值不再生效。想只允许本机访问就把 `SERVER_ADDRESS` 改成 `127.0.0.1`。

> [!WARNING]
> `admin / 123456` 与 `0.0.0.0` 监听的组合只适合可信的本机开发网络：同一网段的任何设备都能访问你的后端并用默认密码登录。在咖啡店、公司或校园等共享网络下开发时，请改用 `SERVER_ADDRESS=127.0.0.1`，或先修改默认密码。生产部署另有一套配置，见 [Docker Compose](#docker-compose)。

`./run.sh server` 启动后会打印可直接用于真机调试的局域网地址：

```text
[上岸] 启动 Spring Boot，监听 0.0.0.0:18080
[上岸] 本机访问：http://127.0.0.1:18080 （管理后台 /admin）
[上岸] 局域网访问：http://192.168.2.7:18080 （真机调试填这个）
```

其他常用启动方式：

```bash
./run.sh server   # 只启动 Spring Boot
./run.sh ios      # 只启动 iPhone 模拟器与 Flutter
```

真机调试时把上面的局域网地址填进 App 的服务端设置页，或用 `API_BASE_URL` 覆盖：

```bash
API_BASE_URL=http://192.168.2.7:18080 ./run.sh ios
```

启动后可访问：

- App API 与健康检查：`http://127.0.0.1:18080/actuator/health`
- 内部管理后台：`http://127.0.0.1:18080/admin`

### 执行验证

```bash
./test.sh          # 服务端与 Flutter 完整验证
./test.sh server   # 仅服务端逻辑、协议与 Controller 测试
./test.sh ios      # Flutter 格式、静态分析和测试

make format
make verify
```

### Docker Compose

#### 本地构建

```bash
cp .env.example .env
# 编辑 .env，并通过安全方式填入 JWT、播放票据、管理员及外部服务配置。

# 先验证并生成服务端 JAR，再构建容器。
make server-test
docker compose --env-file .env -f infra/compose.yml up --build -d
```

#### 使用 GitHub 发布镜像部署

生产部署直接拉取 `ghcr.io/kiliter/ashorex-server:latest`，不需要在服务器上安装 Java、Maven 或构建 JAR。开始前需要准备：

- Docker Engine 和 Docker Compose Plugin。
- 一台使用本地磁盘保存 Docker 数据的服务器；SQLite 不得位于 NAS、NFS 等网络文件系统。
- 已有的 HTTPS 反向代理，以及指向部署服务器的域名。

推荐使用统一脚本完成部署和维护：

```bash
# 打开中文交互菜单，根据提示选择部署、更新或卸载。
./infra/scripts/deploy.sh
```

交互菜单如下：

```text
========== 上岸 Docker 管理 ==========
1. 首次部署 / 重新部署
2. 更新 GitHub 最新镜像
3. 卸载服务（保留 SQLite 和备份）
4. 彻底卸载（永久删除全部数据）
0. 退出
```

需要用于自动化时，也可以直接传入子命令：

```bash
# 首次部署：自动生成 .env.deploy、JWT 密钥和管理员密码。
./infra/scripts/deploy.sh deploy

# 拉取 GitHub 最新镜像并更新，保留全部数据。
./infra/scripts/deploy.sh update

# 卸载容器和镜像，默认保留 SQLite 与备份数据卷。
./infra/scripts/deploy.sh uninstall

# 永久删除容器、镜像、SQLite 和备份数据卷，需要输入 DELETE 二次确认。
./infra/scripts/deploy.sh uninstall --purge-data
```

首次部署完成后，脚本会输出随机生成的 `admin` 初始密码。部署配置同时保存在权限为 `600` 的 `.env.deploy` 中；该文件已被 Git 忽略。普通卸载不会删除该文件和数据卷。

以下是不用脚本时的手工部署步骤。

1. 创建部署环境文件：

   ```bash
   cp infra/deploy.env.example .env.deploy
   chmod 600 .env.deploy
   openssl rand -hex 32
   ```

   将生成的随机值和管理员密码写入 `.env.deploy`：

   ```dotenv
   JWT_SECRET=<随机值>
   ADMIN_BOOTSTRAP_PASSWORD=<初始管理员密码>
   ```

   `.env.deploy` 已被 Git 忽略，不得把真实密钥提交到仓库。若 GHCR 包不是公开包，还需要先使用有 `read:packages` 权限的 GitHub Token 执行 `docker login ghcr.io`。

2. 拉取镜像并启动：

   ```bash
   docker compose --env-file .env.deploy -f infra/compose.deploy.yml pull
   docker compose --env-file .env.deploy -f infra/compose.deploy.yml up -d
   docker compose --env-file .env.deploy -f infra/compose.deploy.yml ps
   ```

3. 检查健康状态和启动日志：

   ```bash
   curl --fail http://127.0.0.1:18080/actuator/health
   docker compose --env-file .env.deploy -f infra/compose.deploy.yml logs --tail=200 server
   ```

   健康接口应返回 `UP`，日志中不应出现数据库迁移或配置错误。

4. 配置现有反向代理：

   - 上游地址使用 `http://部署服务器IP:18080`。
   - 对外必须启用 HTTPS，并转发 `Host`、`X-Forwarded-For` 和 `X-Forwarded-Proto`。
   - 保留客户端的 `Range`、`If-Range` 请求头以及上游的 `Content-Range`、`Accept-Ranges` 响应头。
   - 视频响应使用流式转发，不要完整缓冲，并设置足够长的读取超时。
   - 服务端监听 `0.0.0.0:18080`；使用防火墙或安全组限制公网直接访问该端口。

5. 首次配置：

   使用用户名 `admin` 和 `.env.deploy` 中的初始密码登录 `https://你的域名/admin`，然后在「运行配置」里填写 Emby 与 Server 酱，并在「督学关系」里建立绑定。Emby 位于 Docker 宿主机时可填写 `http://host.docker.internal:8096`；不要填写容器自身的 `localhost`。

6. 更新 GitHub 镜像：

   ```bash
   docker compose --env-file .env.deploy -f infra/compose.deploy.yml pull
   docker compose --env-file .env.deploy -f infra/compose.deploy.yml up -d --remove-orphans
   ```

停止服务可执行以下命令；不要附加 `-v`，否则会删除 SQLite 和备份数据卷：

```bash
docker compose --env-file .env.deploy -f infra/compose.deploy.yml down
```

生产部署使用 Docker 本地卷 `study-data` 和 `study-backup`。同一 SQLite 数据卷只能由一个服务实例使用，备份和恢复步骤见[运行手册](docs/runbooks/backup-restore.md)。

## GitHub Actions 构建产物

| 产物 | 获取方式 | 说明 |
|---|---|---|
| 无签名 IPA | [Releases](https://github.com/kiliter/Ashorex/releases) 或手动运行“移动端验证” | 需要自行签名后才能安装到真机。 |
| Android 正式签名 APK | [Releases](https://github.com/kiliter/Ashorex/releases) 或手动运行“移动端验证” | 可安装的正式签名 Android 安装包。 |
| Docker 镜像归档 | [Releases](https://github.com/kiliter/Ashorex/releases) 或手动运行“服务端验证” | 下载后可通过 `docker load` 导入。 |
| GHCR 镜像 | `ghcr.io/kiliter/ashorex-server:latest` | `main` 分支验证通过后推送。 |

普通 Push 和 Pull Request 只执行验证，不保存大体积 Artifact。手动运行工作流时，临时 Artifact 保留 3 天；推送 `v*` 标签时，三个安装产物会自动进入对应 GitHub Release。也可以直接拉取最新服务端镜像：

```bash
docker pull ghcr.io/kiliter/ashorex-server:latest
```

## 仓库结构

```text
Ashorex/
├── apps/
│   ├── ios/        # Flutter 移动端；目录名为历史命名，同时支持 iOS、iPadOS 和 Android
│   ├── server/     # Spring Boot 模块化单体，同时托管管理后台静态资源
│   └── admin-web/  # 内部管理后台（Vue 3 SPA），构建产物打进服务端
├── docs/
│   ├── specs/      # 冻结的产品与技术设计规范
│   ├── plans/      # 实施计划
│   ├── traceability/ # 需求—实现—验收追踪矩阵
│   ├── roadmap/    # 未来版本方向与进入开发前的门禁，不是实施计划
│   ├── prototypes/ # 仓库内产品原型快照，不依赖个人电脑绝对路径
│   ├── adr/        # 架构决策记录
│   ├── api/        # OpenAPI 合同
│   └── runbooks/   # 备份恢复与真机验收手册
├── infra/          # Docker、Caddy 与运维脚本
├── run.sh          # 本地统一启动入口
├── test.sh         # 本地统一验证入口
└── Makefile        # CI 与开发命令
```

## 项目文档

修改代码前请依次阅读：

1. [协作与工程约束](AGENTS.md)
2. [V2 产品与技术设计规范](docs/specs/2026-09-07-shangan-v2-design.md)
3. [V2 实施计划](docs/plans/2026-09-07-shangan-v2-implementation-plan.md)
4. [需求—实现—验收追踪矩阵](docs/traceability/2026-09-07-shangan-v2-traceability.md)
5. [UI 事实来源：V2 高保真原型](docs/prototypes/shangan-v2-prototype.html)
6. 与改动相关的 [ADR](docs/adr/)（V2 为 ADR-0025 ~ ADR-0035）

API 变更必须同步更新 [OpenAPI 合同](docs/api/openapi.yaml)。本地只运行本次新增或修改对应的窄测试；全量 `make verify` 由 GitHub CI 执行。

V1 的需求三件套、旧原型与已被取代的 ADR 已随 V2 重构删除，如需查阅请从 `v1-final` 标签的 Git 历史中获取。

## V2 边界

- 客户端覆盖 iPhone、iPad 和 Android；不提供 PC 学习 Web 或桌面客户端。
- 不实现可信播放、学习欠债、答题题库、模拟考试、日终结算与晚间审判。
- 不实现任何 AI 能力：无 ASR、无 LLM、无摘要、无题目草稿、无 AI Chat。
- 不实现支付、商城、直播、社交、离线视频、DRM、微服务、Redis、Kafka 或向量数据库。
- 材料（PDF）只预留数据模型与开关，阅读页与书籍库同步不在 V2 范围。
- 真实视频播放必须在物理 iPhone、iPad 和 Android 设备完成 smoke test；模拟器结果不能替代真机验收。

## 安全与许可

- 不要提交 JWT Secret、管理员密码、Emby API Key、Server 酱 SendKey 或生产地址凭据。
- 生产部署使用 HTTPS；刷新令牌以哈希形式保存，管理后台使用 HttpOnly Session Cookie 与 CSRF。
- 仓库当前尚未提供 `LICENSE`，因此不会自动授予复制、修改或分发权限。公开协作或再分发前，应由维护者选择许可证并补充 `LICENSE` 文件。

---

<p align="center">为每一次真实学习留下证据，也为每一次未完成留下去处。</p>
