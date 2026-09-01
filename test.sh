#!/usr/bin/env bash

# 上岸本地统一测试入口。
# 默认按照项目 Definition of Done 执行服务端、Flutter 和备份恢复验证，也可只跑单个模块。
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SERVER_DIR="$PROJECT_DIR/apps/server"
readonly IOS_DIR="$PROJECT_DIR/apps/ios"

FLUTTER_COMMAND=()
DART_COMMAND=()

print_usage() {
  cat <<'USAGE'
用法：
  ./test.sh             执行全部验证（默认）
  ./test.sh all         同上
  ./test.sh server      只执行服务端 verify
  ./test.sh ios         只执行 Flutter 格式检查、静态分析和测试
  ./test.sh operations  只执行 SQLite 备份恢复验证
  ./test.sh --help      显示帮助
USAGE
}

log() {
  printf '\n[上岸测试] %s\n' "$*"
}

fail() {
  printf '[上岸测试] 错误：%s\n' "$*" >&2
  exit 1
}

java_major_version() {
  local java_binary="$1"
  "$java_binary" -version 2>&1 | awk -F'[".]' '/version/ { print $2; exit }'
}

# 测试脚本不依赖当前终端是否已执行 sdk env，会主动定位本机 Java 21。
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
}

resolve_flutter_commands() {
  if [[ -x "$IOS_DIR/.fvm/flutter_sdk/bin/flutter" && -x "$IOS_DIR/.fvm/flutter_sdk/bin/dart" ]]; then
    FLUTTER_COMMAND=("$IOS_DIR/.fvm/flutter_sdk/bin/flutter")
    DART_COMMAND=("$IOS_DIR/.fvm/flutter_sdk/bin/dart")
  elif command -v fvm >/dev/null 2>&1; then
    FLUTTER_COMMAND=(fvm flutter)
    DART_COMMAND=(fvm dart)
  elif command -v flutter >/dev/null 2>&1 && command -v dart >/dev/null 2>&1; then
    FLUTTER_COMMAND=(flutter)
    DART_COMMAND=(dart)
  else
    fail '未找到 Flutter/FVM，请先安装项目锁定的 Flutter SDK。'
  fi
}

test_server() {
  configure_java_21
  log "服务端验证（Java $(java_major_version "$JAVA_HOME/bin/java")）"
  (
    cd "$SERVER_DIR"
    ./mvnw verify
  )
}

test_ios() {
  resolve_flutter_commands
  log '解析 Flutter 依赖'
  (
    cd "$IOS_DIR"
    "${FLUTTER_COMMAND[@]}" pub get

    log '检查 Dart 格式'
    "${DART_COMMAND[@]}" format --output=none --set-exit-if-changed lib test integration_test

    log '执行 Flutter 静态分析'
    "${FLUTTER_COMMAND[@]}" analyze

    log '执行 Flutter 自动化测试'
    "${FLUTTER_COMMAND[@]}" test
  )
}

test_operations() {
  log '执行 SQLite 备份、完整性检查和恢复验证'
  "$PROJECT_DIR/infra/scripts/backup_restore_smoke_test.sh"
}

test_all() {
  test_server
  test_ios
  test_operations
}

case "${1:-all}" in
all)
  test_all
  ;;
server)
  test_server
  ;;
ios)
  test_ios
  ;;
operations)
  test_operations
  ;;
-h | --help | help)
  print_usage
  ;;
*)
  print_usage >&2
  fail "未知参数：$1"
  ;;
esac
