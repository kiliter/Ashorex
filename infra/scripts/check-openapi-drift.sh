#!/usr/bin/env bash

# 校验 docs/api/openapi.yaml 与服务端实际路由结构一致。
#
# 比对方式是「结构比对」而不是逐字节 diff：手写冻结合同带中文 description、示例和
# 手写错误码说明，springdoc 只能从代码推断骨架，逐字节比对必然恒红。真正的契约是
# 路径 + 方法集合、必填参数、必填请求体字段和响应状态码，由
# infra/scripts/compare_openapi_contract.py 负责判定。
#
# 运行方式：使用一次性临时 DATA_DIR 与随机空闲端口启动服务，导出 springdoc 文档后比对，
# 不触碰真实开发数据库，也不会和本地已在运行的服务抢端口。
#
# 可选环境变量：
#   OPENAPI_CHECK_PORT   固定启动端口；不设置时自动选择空闲端口。
#   OPENAPI_RUNTIME_DOC  直接复用已导出的运行时文档（.json/.yaml），跳过启动服务。
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly SERVER_DIR="$PROJECT_DIR/apps/server"
readonly COMMITTED_CONTRACT="$PROJECT_DIR/docs/api/openapi.yaml"
readonly COMPARATOR="$PROJECT_DIR/infra/scripts/compare_openapi_contract.py"

WORK_DIR=""
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    # 启动时禁用 fork，应用就跑在这个进程里；仍兜底清理可能存在的子进程。
    pkill -P "$SERVER_PID" 2>/dev/null || true
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
[[ -f "$COMPARATOR" ]] || fail "找不到结构比对脚本 $COMPARATOR"
command -v python3 >/dev/null 2>&1 || fail "需要 python3 执行结构比对"

WORK_DIR="$(mktemp -d)"
readonly RUNTIME_DOC="$WORK_DIR/runtime-openapi.json"

if [[ -n "${OPENAPI_RUNTIME_DOC:-}" ]]; then
  # 复用外部导出的文档，便于本地反复调试比对逻辑而不必每次启动服务。
  [[ -f "$OPENAPI_RUNTIME_DOC" ]] || fail "OPENAPI_RUNTIME_DOC 指向的文件不存在：$OPENAPI_RUNTIME_DOC"
  printf '[契约检查] 复用已有运行时文档：%s\n' "$OPENAPI_RUNTIME_DOC"
  python3 "$COMPARATOR" --contract "$COMMITTED_CONTRACT" --runtime "$OPENAPI_RUNTIME_DOC" ||
    fail "docs/api/openapi.yaml 与实际路由结构不一致，请按上面列出的差异修正"
  exit 0
fi

# 随机空闲端口：避免与本地开发服务或并发 CI 任务抢占固定端口。
PORT="${OPENAPI_CHECK_PORT:-}"
if [[ -z "$PORT" ]]; then
  PORT="$(python3 - <<'PY'
import socket

with socket.socket() as probe:
    probe.bind(("127.0.0.1", 0))
    print(probe.getsockname()[1])
PY
)"
fi
readonly PORT

# 临时密钥仅用于本次启动，不写入仓库，也不影响任何已部署环境。
# -Dspring-boot.run.fork=false 让应用跑在 Maven 进程内，脚本退出时能确定地收回进程。
JWT_SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')" \
  DATA_DIR="$WORK_DIR" \
  SERVER_PORT="$PORT" \
  "$SERVER_DIR/mvnw" -f "$SERVER_DIR/pom.xml" -q -Dspring-boot.run.fork=false spring-boot:run \
  >"$WORK_DIR/boot.log" 2>&1 &
SERVER_PID=$!

printf '[契约检查] 等待服务端在 %s 端口就绪\n' "$PORT"
ready=0
for _ in $(seq 1 90); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null; then
    ready=1
    break
  fi
  kill -0 "$SERVER_PID" 2>/dev/null ||
    { tail -n 40 "$WORK_DIR/boot.log" >&2; fail "服务端启动失败，上方为启动日志末尾"; }
  sleep 2
done
if [[ "$ready" -ne 1 ]]; then
  tail -n 40 "$WORK_DIR/boot.log" >&2
  fail "服务端在 180 秒内未就绪，上方为启动日志末尾"
fi

curl -fsS "http://127.0.0.1:$PORT/v3/api-docs" -o "$RUNTIME_DOC" ||
  fail "无法导出实际 OpenAPI 文档"

python3 "$COMPARATOR" --contract "$COMMITTED_CONTRACT" --runtime "$RUNTIME_DOC" ||
  fail "docs/api/openapi.yaml 与实际路由结构不一致，请按上面列出的差异修正"
