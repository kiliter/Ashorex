# SDKMAN 自动切换 Java 21 设计

## 目标

进入上岸项目目录时，SDKMAN 自动把当前终端切换到项目要求的 Java 21，避免默认 Java 17 导致 Spring Boot 启动失败；离开项目后不修改其他项目的版本约束。

## 当前环境

- SDKMAN 当前默认 Java：`17.0.12-graal`。
- SDKMAN 已安装的 Java 21：`21.0.7.fx-zulu`。
- SDKMAN 目录自动切换当前为关闭状态：`sdkman_auto_env=false`。

## 方案

1. 在仓库根目录提交 `.sdkmanrc`，固定 `java=21.0.7.fx-zulu`。
2. 将当前用户的 SDKMAN 配置改为 `sdkman_auto_env=true`，让进入仓库目录时自动执行项目版本切换。
3. 不修改系统级 `JAVA_HOME`，不改变 SDKMAN 的全局默认 Java。
4. 在项目说明中记录 `sdk env` 手动切换命令，便于自动切换未生效时排查。

## 错误处理

- 如果其他开发机器未安装 `21.0.7.fx-zulu`，SDKMAN 会提示候选版本缺失；开发者可以执行 `sdk env install` 安装 `.sdkmanrc` 指定版本。
- 如果当前 Shell 尚未初始化 SDKMAN，需要先加载 `$HOME/.sdkman/bin/sdkman-init.sh` 或重新打开终端。
- Java 版本校验必须确认 `java -version` 和 `./apps/server/mvnw --version` 均运行在 Java 21。

## 验证

1. 从仓库外进入仓库根目录，确认 SDKMAN 自动切换到 `21.0.7.fx-zulu`。
2. 执行 `java -version`，确认主版本为 21。
3. 执行 `cd apps/server && ./mvnw --version`，确认 Maven 使用 Java 21。
4. 启动服务端并确认 `/actuator/health` 返回 `UP`。

## 安全与影响范围

- `.sdkmanrc` 只记录公开的 JDK 候选版本，不包含密钥或密码。
- SDKMAN 用户配置只开启目录自动切换，不提交到仓库。
- CI、生产容器和其他项目不依赖本机 SDKMAN 配置，因此不受影响。
