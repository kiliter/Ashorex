#!/usr/bin/env bash

# 上岸本地开发启动器。
# 默认同时启动 Spring Boot、iPhone 模拟器和 Flutter，并在退出时清理本脚本启动的后端进程。
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SERVER_DIR="$PROJECT_DIR/apps/server"
readonly IOS_DIR="$PROJECT_DIR/apps/ios"
readonly ADMIN_WEB_DIR="$PROJECT_DIR/apps/admin-web"
readonly RUNTIME_DIR="$PROJECT_DIR/.run"
readonly LOCAL_ENV_FILE="$PROJECT_DIR/.env.local"
readonly LOCAL_ENV_EXAMPLE="$PROJECT_DIR/.env.local.example"

# 逐行解析 .env.local，只接受合法的 KEY=VALUE，避免 source 执行任意 shell 代码。
# 命令行上已经导出的变量优先级更高，不会被文件覆盖。
load_local_env() {
  local line key value

  if [[ ! -f "$LOCAL_ENV_FILE" ]]; then
    if [[ -f "$LOCAL_ENV_EXAMPLE" ]]; then
      cp "$LOCAL_ENV_EXAMPLE" "$LOCAL_ENV_FILE"
      printf '[上岸] 已从 .env.local.example 生成 .env.local，可按需修改。\n'
    else
      # 两个文件都不存在时静默跳过；必须显式 return 0，
      # 否则裸 return 会带回上一条判断的非零码并在 set -e 下终止整个脚本。
      return 0
    fi
  fi

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%$'\r'}"
    if [[ "$line" =~ ^[[:space:]]*# ]] || [[ "$line" =~ ^[[:space:]]*$ ]]; then
      continue
    fi
    if [[ ! "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      continue
    fi

    key="${BASH_REMATCH[2]}"
    value="${BASH_REMATCH[3]}"
    # 去掉尾随空白与成对引号，不做变量展开或命令替换。
    value="${value%"${value##*[![:space:]]}"}"
    if [[ ${#value} -ge 2 && "$value" == \"*\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ ${#value} -ge 2 && "$value" == \'*\' ]]; then
      value="${value:1:${#value}-2}"
    fi

    # 显式导出的空值也优先于配置文件，允许临时关闭外部服务。
    # 注意：set -e 下不能写 `[[ ... ]] && continue`，条件为假会让整条语句返回 1 并终止脚本。
    if [[ -z "${!key+x}" ]]; then
      export "$key=$value"
    fi
  done <"$LOCAL_ENV_FILE"
  return 0
}

load_local_env

# 这些值需要传给 Spring Boot，因此直接导出为普通环境变量。
# 不用 readonly：它与后续 export 组合在 set -euo 下极易触发难排查的静默退出。
SERVER_ADDRESS="${SERVER_ADDRESS:-0.0.0.0}"
SERVER_PORT="${SERVER_PORT:-18080}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:$SERVER_PORT/actuator/health}"
ADMIN_DEV_PROXY="${ADMIN_DEV_PROXY:-http://127.0.0.1:$SERVER_PORT}"
export SERVER_ADDRESS SERVER_PORT

SERVER_PID=""
FLUTTER_COMMAND=()

print_usage() {
  cat <<'USAGE'
用法：
  ./run.sh          启动后端、iPhone 模拟器和 Flutter（默认）
  ./run.sh all      同上
  ./run.sh server   只在前台启动 Spring Boot（先构建一次后台产物请用 admin-build）
  ./run.sh admin    启动后端 + 管理后台 Vite 开发服务器（后台开发用，带热更新）
  ./run.sh admin-build  只构建管理后台静态产物
  ./run.sh seed     写入开发用种子数据（需后端已启动）
  ./run.sh ios      只启动 iPhone 模拟器和 Flutter
  ./run.sh --help   显示帮助

可选环境变量（也可写入 .env.local，命令行优先级更高）：
  API_BASE_URL       Flutter 使用的服务端地址，默认 http://127.0.0.1:18080
  SERVER_ADDRESS     Spring Boot 监听地址，默认 0.0.0.0；只想本机访问时设为 127.0.0.1
  SERVER_PORT        Spring Boot 本地端口，默认 18080
  FLUTTER_DEVICE_ID  指定模拟器 UDID；未设置时自动复用或启动一个 iPhone 模拟器
  JWT_SECRET         覆盖本地 JWT 密钥；未设置时使用 .env.local 中持久化的随机密钥
  ADMIN_BOOTSTRAP_USERNAME / ADMIN_BOOTSTRAP_PASSWORD
                     首次管理员，默认 admin / 123456；仅在库中还没有管理员时生效
  LOGGING_LEVEL_ROOT   Spring 根日志级别，默认 WARN；排查问题时设为 INFO
  LOGGING_LEVEL_COM_SHANGAN  com.shangan 包日志级别，默认 WARN（含每请求日志）
  ADMIN_DEV_PROXY    后台 dev server 的接口代理目标，默认 http://127.0.0.1:18080
  FAKE_EMBY          仅 seed 使用：本机假 Emby 地址（见 scripts/fake_emby.py）。
                     设置后 seed 会写入 Emby 运行配置、绑定媒体库并建课同步，
                     用于在没有真实 Emby 时验证课程库与 Emby 同步两个后台页面。

配置文件：
  首次运行会自动从 .env.local.example 复制出 .env.local（已被 Git 忽略）。
  修改 .env.local 后重启即可生效；不要把真实密钥写进 .env.local.example。

开发说明：
  后端直接读取 src/main/resources 下的模板和静态文件。
  修改 HTML、CSS 或 JavaScript 后刷新浏览器即可，无需重启后端；Java 代码改动仍需重启。
USAGE
}

log() {
  # 写 stderr：exec 替换进程映像时不会刷新 stdout 缓冲，提示会丢失。
  printf '[上岸] %s\n' "$*" >&2
}

fail() {
  printf '[上岸] 错误：%s\n' "$*" >&2
  exit 1
}

java_major_version() {
  local java_binary="$1"
  "$java_binary" -version 2>&1 | awk -F'[".]' '/version/ { print $2; exit }'
}

# 优先复用当前 Java 21；macOS 未切换版本时通过 java_home 自动定位 Java 21。
configure_java_21() {
  local candidate=""

  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] &&
    [[ "$(java_major_version "$JAVA_HOME/bin/java")" == "21" ]]; then
    candidate="$JAVA_HOME"
  elif command -v java >/dev/null 2>&1 && [[ "$(java_major_version "$(command -v java)")" == "21" ]]; then
    candidate="$(
      java -XshowSettings:properties -version 2>&1 |
        awk -F'= ' '/^[[:space:]]*java.home =/ { print $2; exit }'
    )"
  elif [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi

  [[ -n "$candidate" && -x "$candidate/bin/java" ]] ||
    fail '未找到 Java 21。请安装 Java 21，或在项目目录执行 sdk env。'

  export JAVA_HOME="$candidate"
  export PATH="$JAVA_HOME/bin:$PATH"
  log "使用 Java $(java_major_version "$JAVA_HOME/bin/java")：$JAVA_HOME"
}

# JWT 统一保存到本地环境文件；迁移旧密钥以保持已有登录令牌有效。
configure_local_jwt_secret() {
  mkdir -p "$RUNTIME_DIR"
  if [[ -z "${JWT_SECRET:-}" ]]; then
    if [[ -s "$RUNTIME_DIR/jwt-secret" ]]; then
      IFS= read -r JWT_SECRET <"$RUNTIME_DIR/jwt-secret"
    else
      JWT_SECRET="$(openssl rand -hex 32)" || fail '生成 JWT 密钥失败。'
    fi
    # 空配置行移除后追加，避免 IDE 环境文件解析器读取到不同的值。
    local temporary_file
    temporary_file="$(mktemp "$RUNTIME_DIR/env.XXXXXX")" || fail '无法创建临时配置。'
    sed '/^[[:space:]]*JWT_SECRET=/d' "$LOCAL_ENV_FILE" >"$temporary_file"
    printf '\nJWT_SECRET=%s\n' "$JWT_SECRET" >>"$temporary_file"
    chmod 600 "$temporary_file"
    mv "$temporary_file" "$LOCAL_ENV_FILE"
    export JWT_SECRET
  fi
  [[ ${#JWT_SECRET} -ge 32 ]] || fail 'JWT_SECRET 至少需要 32 字节。'
}

# 数据目录必须在启动前存在且为绝对路径：后端进程工作目录是 apps/server，
# 相对路径会落到 apps/server 下并造成困惑；目录缺失时 SQLite 直接报 SQLITE_CANTOPEN。
configure_data_dirs() {
  local key path

  for key in DATA_DIR BACKUP_DIR ATTACHMENTS_DIR; do
    path="${!key:-}"
    [[ -z "$path" ]] && continue
    # 相对路径统一以仓库根为基准解析。
    [[ "$path" != /* ]] && path="$PROJECT_DIR/${path#./}"
    mkdir -p "$path"
    export "$key=$path"
  done

  export DATA_DIR="${DATA_DIR:-$PROJECT_DIR/data}"
  mkdir -p "$DATA_DIR"
  log "数据目录：$DATA_DIR"
  return 0
}


# 打印可用于真机调试的局域网地址，避免手工查 IP。
# 探测失败不影响启动，因此所有分支都显式兜底，不使用裸 && 组合。
print_reachable_urls() {
  local lan_ip=""

  log "本机访问：http://127.0.0.1:${SERVER_PORT} （管理后台 /admin）"

  if [[ "$SERVER_ADDRESS" != "0.0.0.0" ]]; then
    return 0
  fi

  if [[ "$(uname -s)" == "Darwin" ]]; then
    lan_ip="$(ipconfig getifaddr en0 2>/dev/null)" || lan_ip=""
    if [[ -z "$lan_ip" ]]; then
      lan_ip="$(ipconfig getifaddr en1 2>/dev/null)" || lan_ip=""
    fi
  else
    lan_ip="$(hostname -I 2>/dev/null | awk '{print $1}')" || lan_ip=""
  fi

  if [[ -n "$lan_ip" ]]; then
    log "局域网访问：http://${lan_ip}:${SERVER_PORT} （真机调试填这个）"
  fi
  log '当前监听 0.0.0.0，同网段设备均可访问；请勿在不可信网络下使用默认弱密码。'
  return 0
}

# 后台前端：确保依赖已安装。node 版本要求见 apps/admin-web/package.json。
ensure_admin_web_deps() {
  command -v npm >/dev/null 2>&1 || fail '未找到 npm。构建管理后台需要 Node.js 22.12 以上。'
  if [[ ! -d "$ADMIN_WEB_DIR/node_modules" ]]; then
    log '首次运行：安装管理后台前端依赖…'
    (cd "$ADMIN_WEB_DIR" && npm install --no-audit --no-fund >/dev/null 2>&1) ||
      fail '管理后台依赖安装失败，请手动执行 cd apps/admin-web && npm install。'
  fi
}

# 一次性构建后台产物到 Spring 的静态资源目录。
build_admin_web() {
  ensure_admin_web_deps
  log '构建管理后台（Vue）…'
  (cd "$ADMIN_WEB_DIR" && npm run build >/dev/null 2>&1) ||
    fail '管理后台构建失败，请手动执行 cd apps/admin-web && npm run build 查看详情。'
  log '管理后台已输出到 apps/server/src/main/resources/static/admin。'
}

# 以 Vite dev server 方式启动后台，带热更新；输出统一加 [后台] 前缀便于与 Java 区分。
run_admin_web_dev() {
  ensure_admin_web_deps
  log "启动管理后台开发服务器：http://127.0.0.1:5273/admin/"
  log "接口代理到 ${ADMIN_DEV_PROXY}"
  cd "$ADMIN_WEB_DIR"
  ADMIN_DEV_PROXY="$ADMIN_DEV_PROXY" exec npm run dev --silent
}

resolve_flutter_command() {
  if [[ -x "$IOS_DIR/.fvm/flutter_sdk/bin/flutter" ]]; then
    FLUTTER_COMMAND=("$IOS_DIR/.fvm/flutter_sdk/bin/flutter")
  elif command -v fvm >/dev/null 2>&1; then
    FLUTTER_COMMAND=(fvm flutter)
  elif command -v flutter >/dev/null 2>&1; then
    FLUTTER_COMMAND=(flutter)
  else
    fail '未找到 Flutter。请先安装 FVM，并在 apps/ios 中执行 fvm install。'
  fi
}

server_is_healthy() {
  command -v curl >/dev/null 2>&1 || fail '健康检查需要 curl。'
  curl --fail --silent --max-time 2 "$HEALTH_URL" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'
}

run_server_foreground() {
  # 配置阶段允许个别检查返回非零（例如可选变量缺省），不应因此终止启动；
  # 真正的致命错误由各函数内部的 fail 显式退出。
  set +e
  configure_java_21
  configure_local_jwt_secret
  configure_data_dirs
  set -e
  log "启动 Spring Boot，监听 ${SERVER_ADDRESS}:${SERVER_PORT}（日志级别 ${LOGGING_LEVEL_ROOT:-WARN}）"
  print_reachable_urls
  cd "$PROJECT_DIR" || fail "找不到仓库目录：$PROJECT_DIR"
  # exec 会替换进程映像；先跑一个外部命令产生 fork，促使上面的提示落盘，
  # 否则输出重定向到文件时这几行会连同缓冲一起丢失。
  /usr/bin/true
  # -q 关闭 Maven 自身的构建日志；skip.admin.web 避免每次重启都跑一遍 npm。
  exec "$SERVER_DIR/mvnw" -f "$SERVER_DIR/pom.xml" -q -Dskip.admin.web=true -Dspring-boot.run.workingDirectory="$PROJECT_DIR" spring-boot:run
}

start_server_background() {
  local server_log="$RUNTIME_DIR/server.log"
  local attempt

  if server_is_healthy; then
    log '检测到健康的后端服务，直接复用。'
    return
  fi

  if lsof -nP -iTCP:"$SERVER_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    fail "$SERVER_PORT 端口已被占用，但健康检查未通过。请先处理占用进程。"
  fi

  set +e
  configure_java_21
  configure_local_jwt_secret
  configure_data_dirs
  set -e
  log "启动 Spring Boot，日志写入 $server_log"
  print_reachable_urls
  (
    cd "$PROJECT_DIR"
    exec "$SERVER_DIR/mvnw" -f "$SERVER_DIR/pom.xml" -q -Dskip.admin.web=true -Dspring-boot.run.workingDirectory="$PROJECT_DIR" spring-boot:run
  ) >"$server_log" 2>&1 &
  SERVER_PID=$!

  for attempt in $(seq 1 60); do
    if server_is_healthy; then
      log '后端健康检查通过。'
      return
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      tail -n 80 "$server_log" >&2 || true
      fail 'Spring Boot 启动失败，错误日志见上方。'
    fi
    sleep 1
  done

  tail -n 80 "$server_log" >&2 || true
  fail '等待后端健康检查超时。'
}

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    log '停止本脚本启动的 Spring Boot。'
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}

# 复用已启动的 iPhone；没有可用设备时自动启动列表中的第一个 iPhone 模拟器。
resolve_iphone_simulator() {
  local device_id="${FLUTTER_DEVICE_ID:-}"

  [[ "$(uname -s)" == "Darwin" ]] || fail 'iOS 模拟器只能在 macOS 上运行。'
  command -v xcrun >/dev/null 2>&1 || fail '未找到 Xcode 命令行工具 xcrun。'

  if [[ -z "$device_id" ]]; then
    device_id="$(
      xcrun simctl list devices booted |
        sed -nE 's/^[[:space:]]+iPhone[^\(]*\(([0-9A-F-]{36})\).*/\1/p' |
        head -n 1
    )"
  fi

  if [[ -z "$device_id" ]]; then
    device_id="$(
      xcrun simctl list devices available |
        sed -nE 's/^[[:space:]]+iPhone[^\(]*\(([0-9A-F-]{36})\)[[:space:]]+\(Shutdown\).*/\1/p' |
        head -n 1
    )"
    [[ -n "$device_id" ]] || fail '没有找到可用的 iPhone 模拟器，请先在 Xcode 中安装 iOS Simulator Runtime。'
    log "启动 iPhone 模拟器：$device_id" >&2
    xcrun simctl boot "$device_id"
    xcrun simctl bootstatus "$device_id" -b
  else
    log "复用 iPhone 模拟器：$device_id" >&2
  fi

  open -a Simulator
  printf '%s' "$device_id"
}

run_ios() {
  local device_id
  local api_base_url="${API_BASE_URL:-http://127.0.0.1:$SERVER_PORT}"

  resolve_flutter_command
  device_id="$(resolve_iphone_simulator)"
  log "启动 Flutter，API 地址：$api_base_url"
  cd "$IOS_DIR"
  # 使用同一次构建产物安装运行，避免旧安装包与热更新代码混用。
  # 模拟器默认验证 AVPlayer；显式设置环境变量仍可对照 media_kit。
  local playback_engine="${PLAYBACK_ENGINE:-video_player}"
  case "$playback_engine" in
    video_player|media_kit) ;;
    *) fail 'PLAYBACK_ENGINE 只支持 video_player 或 media_kit。' ;;
  esac
  log "本次播放内核：$playback_engine；先构建，再安装运行同一份 App。"
  "${FLUTTER_COMMAND[@]}" build ios --simulator --debug \
    --dart-define="API_BASE_URL=$api_base_url" --dart-define="PLAYBACK_ENGINE=$playback_engine"
  "${FLUTTER_COMMAND[@]}" run -d "$device_id" --no-enable-impeller \
    --use-application-binary="$IOS_DIR/build/ios/iphonesimulator/Runner.app" \
    --dart-define="API_BASE_URL=$api_base_url" --dart-define="PLAYBACK_ENGINE=$playback_engine"
}

run_all() {
  trap cleanup EXIT INT TERM
  build_admin_web
  start_server_background
  run_ios
}

# 同时启动 Spring Boot（后台进程）与后台前端 dev server（前台）。
# Java 日志压到 WARN 并写入 .run/server.log，终端只留 Vite 输出，便于区分两者。
run_admin_stack() {
  trap cleanup EXIT INT TERM
  start_server_background
  log '后端已就绪，Java 日志见 .run/server.log；以下输出来自 Vite。'
  run_admin_web_dev
}

case "${1:-all}" in
all)
  run_all
  ;;
server)
  run_server_foreground
  ;;
admin)
  run_admin_stack
  ;;
admin-build)
  build_admin_web
  ;;
seed)
  # 种子数据走真实 API 写入，需要后端已在运行。
  BASE="http://127.0.0.1:${SERVER_PORT}" \
    ADMIN_BOOTSTRAP_USERNAME="${ADMIN_BOOTSTRAP_USERNAME:-admin}" \
    ADMIN_BOOTSTRAP_PASSWORD="${ADMIN_BOOTSTRAP_PASSWORD:-123456}" \
    exec python3 "$PROJECT_DIR/scripts/seed_demo.py"
  ;;
ios)
  run_ios
  ;;
-h | --help | help)
  print_usage
  ;;
*)
  print_usage >&2
  fail "未知参数：$1"
  ;;
esac
