#!/usr/bin/env bash

# 上岸本地开发启动器。
# 默认同时启动 Spring Boot、iPhone 模拟器和 Flutter，并在退出时清理本脚本启动的后端进程。
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SERVER_DIR="$PROJECT_DIR/apps/server"
readonly IOS_DIR="$PROJECT_DIR/apps/ios"
readonly RUNTIME_DIR="$PROJECT_DIR/.run"
readonly SERVER_PORT="${SERVER_PORT:-18080}"
readonly HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:$SERVER_PORT/actuator/health}"

SERVER_PID=""
FLUTTER_COMMAND=()

print_usage() {
  cat <<'USAGE'
用法：
  ./run.sh          启动后端、iPhone 模拟器和 Flutter（默认）
  ./run.sh all      同上
  ./run.sh server   只在前台启动 Spring Boot
  ./run.sh ios      只启动 iPhone 模拟器和 Flutter
  ./run.sh --help   显示帮助

可选环境变量：
  API_BASE_URL       Flutter 使用的服务端地址，默认 http://127.0.0.1:18080
  SERVER_PORT        Spring Boot 本地端口，默认 18080
  FLUTTER_DEVICE_ID  指定模拟器 UDID；未设置时自动复用或启动一个 iPhone 模拟器
  JWT_SECRET         覆盖本地 JWT 密钥；未设置时使用 .run/jwt-secret 中的随机密钥
USAGE
}

log() {
  printf '[上岸] %s\n' "$*"
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

# 密钥只保存在被 Git 忽略的本地运行目录，避免每次重启让已有登录令牌失效。
configure_local_jwt_secret() {
  local secret_file="$RUNTIME_DIR/jwt-secret"
  mkdir -p "$RUNTIME_DIR"

  if [[ -z "${JWT_SECRET:-}" ]]; then
    if [[ ! -s "$secret_file" ]]; then
      command -v openssl >/dev/null 2>&1 || fail '生成本地开发密钥需要 openssl。'
      umask 077
      openssl rand -hex 32 >"$secret_file"
    fi
    IFS= read -r JWT_SECRET <"$secret_file"
    export JWT_SECRET
  fi

  [[ ${#JWT_SECRET} -ge 32 ]] || fail 'JWT_SECRET 至少需要 32 字节。'
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
  configure_java_21
  configure_local_jwt_secret
  log "启动 Spring Boot：http://127.0.0.1:$SERVER_PORT"
  cd "$SERVER_DIR"
  exec ./mvnw spring-boot:run
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

  configure_java_21
  configure_local_jwt_secret
  log "启动 Spring Boot，日志写入 $server_log"
  (
    cd "$SERVER_DIR"
    exec ./mvnw spring-boot:run
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
  "${FLUTTER_COMMAND[@]}" run -d "$device_id" --dart-define="API_BASE_URL=$api_base_url"
}

run_all() {
  trap cleanup EXIT INT TERM
  start_server_background
  run_ios
}

case "${1:-all}" in
all)
  run_all
  ;;
server)
  run_server_foreground
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
