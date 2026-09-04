#!/usr/bin/env bash

# 校验 docs/api/openapi.yaml 与服务端实际路由一致。
# 使用一次性临时 DATA_DIR 启动服务，导出 springdoc 合同后比对，不触碰真实数据库。
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly SERVER_DIR="$PROJECT_DIR/apps/server"
readonly COMMITTED_CONTRACT="$PROJECT_DIR/docs/api/openapi.yaml"
readonly PORT="${OPENAPI_CHECK_PORT:-18098}"

WORK_DIR=""
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]] && rm -rf "$WORK_DIR"
}
trap cleanup EXIT

fail() {
  printf '[契约检查] 错误：%s\n' "$*" >&2
  exit 1
}

[[ -f "$COMMITTED_CONTRACT" ]] || fail "找不到 $COMMITTED_CONTRACT"

WORK_DIR="$(mktemp -d)"
# 临时密钥仅用于本次启动，不写入仓库，也不影响任何已部署环境。
JWT_SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')" \
  DATA_DIR="$WORK_DIR" \
  SERVER_PORT="$PORT" \
  "$SERVER_DIR/mvnw" -f "$SERVER_DIR/pom.xml" -q spring-boot:run >"$WORK_DIR/boot.log" 2>&1 &
SERVER_PID=$!

printf '[契约检查] 等待服务端在 %s 端口就绪\n' "$PORT"
for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null; then
    break
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || fail "服务端启动失败，详见启动日志"
  sleep 2
done

curl -fsS "http://127.0.0.1:$PORT/v3/api-docs.yaml" -o "$WORK_DIR/actual-openapi.yaml" ||
  fail "无法导出实际 OpenAPI 合同"

if ! diff -u "$COMMITTED_CONTRACT" "$WORK_DIR/actual-openapi.yaml"; then
  fail "docs/api/openapi.yaml 与实际路由不一致，请更新提交的合同"
fi

printf '[契约检查] OpenAPI 合同与实际路由一致\n'
