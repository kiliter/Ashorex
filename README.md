<div align="center">

# 上岸 · Ashorex

**把备考计划、可信学习、学习欠债与复盘连成一个可审计闭环。**

[![移动端验证](https://github.com/kiliter/Ashorex/actions/workflows/ios-verify.yml/badge.svg)](https://github.com/kiliter/Ashorex/actions/workflows/ios-verify.yml)
[![服务端验证](https://github.com/kiliter/Ashorex/actions/workflows/server-verify.yml/badge.svg)](https://github.com/kiliter/Ashorex/actions/workflows/server-verify.yml)
![Flutter 3.44.7](https://img.shields.io/badge/Flutter-3.44.7-02569B?logo=flutter&logoColor=white)
![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![iOS 16+](https://img.shields.io/badge/iOS-16%2B-111111?logo=apple&logoColor=white)
![Android API 24+](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)

[产品预览](#产品预览) · [核心能力](#核心能力) · [快速开始](#快速开始) · [构建产物](#github-actions-构建产物) · [项目文档](#项目文档)

</div>

> [!IMPORTANT]
> 上岸当前处于 V1 开发与真机验收阶段，面向自托管和小规模使用场景。它不是在线教育平台，也不提供课程售卖、社交或 AI Chat。

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

很多学习工具只记录“打开过”或“播放过”，却无法回答三个更重要的问题：今天承诺了什么、实际可信完成了多少、未完成的部分去了哪里。

上岸围绕一条明确的学习闭环工作：

```text
考试目标
  → 编排今日作战单
  → 可信视频学习 / 答题 / 模拟考试 / 专注计时
  → 日终自动结算
  → 未完成承诺形成学习欠债
  → 日报、周报与晚间审判
  → 下一天继续完成或还债
```

## 核心能力

| 能力 | 说明 |
|---|---|
| 今日作战单 | 集中编排课时和模拟考试，原子保存；当天可继续添加，并允许修改尚未开始的项目。 |
| 可信视频学习 | 服务端维护可信最大进度，校验心跳、倍速和前台状态；禁止跳入未验证区间，允许回看。 |
| 进度验活 | 按视频内容进度百分比触发验活，默认每推进 50% 检查一次；等待确认期间不累计可信时长。 |
| 模拟考试与专注 | 使用考试预置启动倒计时并上传试卷照片；番茄钟和练习计时作为独立小工具运行。 |
| 学习欠债 | 日终把作战单内未完成的视频观看与必答内容拆成可追踪欠债，后续学习可精确偿还。 |
| 日报与周报 | 汇总可信学习、答题、专注、欠债、复习审计和日终结果，并生成确定性的晚间审判。 |
| 课程内容生产 | 从 Emby 音频流生成全文、Markdown 摘要和待审核题目草稿；管理员发布后才进入正式题库。 |
| 内部管理后台 | 管理用户、课程、课时、内容任务、题目草稿、外部服务配置和运行状态。 |

## 系统架构

```text
┌──────────────────────────────────┐
│ Flutter Mobile App               │
│ iPhone / iPad / Android          │
│ 首页 · 学习 · 数据 · 我的         │
└───────────────┬──────────────────┘
                │ HTTPS / REST
                ▼
┌──────────────────────────────────┐
│ Spring Boot 模块化单体            │
│ Identity · Catalog · Planning    │
│ Learning · Debt · Reporting      │
│ AI Content · Emby · Admin        │
└───────┬────────────┬─────────────┘
        │            │
        ▼            ├──────────────► Emby
     SQLite          └──────────────► ASR / LLM
```

- 业务真相保存在服务端，Flutter 页面不直接调用 Dio，而是通过 Repository 和 Controller 访问 API。
- 服务端采用单实例、模块化单体和本机 SQLite WAL，目标规模为少于 5 人同时在线。
- Emby、ASR、LLM 与 OpenRouter 密钥只存在于服务端环境或管理后台配置中。
- AI 仅用于课时转写、摘要和待审核题目草稿，不读取或修改计划、欠债、可信进度与报表。

## 技术栈

| 区域 | 技术 |
|---|---|
| 移动端 | Flutter 3.44.7、Dart 3.12、Riverpod、go_router、Dio、video_player |
| 服务端 | Java 21、Spring Boot 4.1.1、Spring MVC、Virtual Threads、JdbcClient |
| 数据与迁移 | SQLite WAL、Flyway |
| 管理后台 | Thymeleaf、Spring Security Session、少量原生 JavaScript |
| 媒体与内容 | Emby、OpenAI-compatible ASR / LLM、LangChain4j |
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

首次创建管理账号时，请在当前终端通过环境变量注入用户名和密码，不要把密码写入仓库：

```bash
export ADMIN_BOOTSTRAP_USERNAME=admin
export ADMIN_BOOTSTRAP_PASSWORD='<仅用于本地开发的密码>'
./run.sh
```

其他常用启动方式：

```bash
./run.sh server   # 只启动 Spring Boot
./run.sh ios      # 只启动 iPhone 模拟器与 Flutter
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

```bash
cp .env.example .env
# 编辑 .env，并通过安全方式填入 JWT、播放票据、管理员及外部服务配置。

# 先验证并生成服务端 JAR，再构建容器。
make server-test
docker compose --env-file .env -f infra/compose.yml up --build -d
```

生产环境必须启用 HTTPS，并把 SQLite 数据卷放在宿主机本地磁盘；同一数据库只能由一个服务实例使用。备份和恢复步骤见[运行手册](docs/runbooks/backup-restore.md)。

## GitHub Actions 构建产物

| 产物 | 获取方式 | 说明 |
|---|---|---|
| 无签名 IPA | [移动端验证](https://github.com/kiliter/Ashorex/actions/workflows/ios-verify.yml) 中的 `shangan-ios-unsigned` | 需要自行签名后才能安装到真机。 |
| Android Debug APK | [移动端验证](https://github.com/kiliter/Ashorex/actions/workflows/ios-verify.yml) 中的 `shangan-android-debug` | 用于开发和内部验证，不是正式发布包。 |
| Docker 镜像归档 | [服务端验证](https://github.com/kiliter/Ashorex/actions/workflows/server-verify.yml) 中的 `ashorex-server-image` | 下载后可通过 `docker load` 导入。 |
| GHCR 镜像 | `ghcr.io/kiliter/ashorex-server:latest` | `main` 分支验证通过后推送。 |

Actions 构建产物保留 14 天。也可以直接拉取最新服务端镜像：

```bash
docker pull ghcr.io/kiliter/ashorex-server:latest
```

## 仓库结构

```text
Ashorex/
├── apps/
│   ├── ios/        # Flutter 移动端；目录名为历史命名，同时支持 iOS、iPadOS 和 Android
│   └── server/     # Spring Boot 模块化单体与 Thymeleaf 管理后台
├── docs/
│   ├── specs/      # 冻结的产品与技术设计规范
│   ├── plans/      # 实施计划
│   ├── traceability/ # 需求—实现—验收追踪矩阵
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
2. [V1 产品与技术设计规范](docs/specs/2026-08-27-shangan-v1-design.md)
3. [V1 实施计划](docs/plans/2026-08-27-shangan-v1-implementation-plan.md)
4. [需求—实现—验收追踪矩阵](docs/traceability/2026-08-27-shangan-v1-traceability.md)
5. 与改动相关的 [ADR](docs/adr/)

API 变更必须同步更新 [OpenAPI 合同](docs/api/openapi.yaml)。从 Task 4 起，改动完成前必须通过窄范围测试与 `make verify`。

## V1 边界

- 客户端覆盖 iPhone、iPad 和 Android；不提供 PC 学习 Web 或桌面客户端。
- 不实现支付、商城、直播、社交、离线视频、DRM、微服务、Redis、Kafka 或向量数据库。
- 不提供 AI Chat、智能体、MCP、联网搜索或 AI 对学习业务数据的写入。
- 真实视频播放必须在物理 iPhone、iPad 和 Android 设备完成 smoke test；模拟器结果不能替代真机验收。

## 安全与许可

- 不要提交 JWT Secret、管理员密码、Emby / ASR / LLM / OpenRouter API Key 或生产地址凭据。
- 生产部署使用 HTTPS；刷新令牌以哈希形式保存，管理后台使用 HttpOnly Session Cookie 与 CSRF。
- 仓库当前尚未提供 `LICENSE`，因此不会自动授予复制、修改或分发权限。公开协作或再分发前，应由维护者选择许可证并补充 `LICENSE` 文件。

---

<p align="center">为每一次真实学习留下证据，也为每一次未完成留下去处。</p>
