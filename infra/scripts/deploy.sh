#!/usr/bin/env bash

# 上岸 Docker 部署入口：统一处理首次部署、镜像更新和安全卸载。
# 普通卸载保留 SQLite 与备份卷；只有显式传入 --purge-data 才允许删除数据。
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly COMPOSE_FILE="${PROJECT_DIR}/infra/compose.deploy.yml"
readonly ENV_FILE="${SHANGAN_ENV_FILE:-${PROJECT_DIR}/.env.deploy}"
readonly PROJECT_NAME="shangan"
GENERATED_ADMIN_PASSWORD=""

log() {
  printf '[上岸部署] %s\n' "$*"
}

fail() {
  printf '[上岸部署] 错误：%s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
用法：
  ./infra/scripts/deploy.sh
  ./infra/scripts/deploy.sh deploy
  ./infra/scripts/deploy.sh update
  ./infra/scripts/deploy.sh uninstall
  ./infra/scripts/deploy.sh uninstall --purge-data

命令：
  无参数       打开中文交互菜单
  deploy       首次部署；不存在 .env.deploy 时自动生成密钥和管理员密码
  update       拉取最新 GitHub 镜像并滚动重建服务，保留全部数据
  uninstall    删除容器和服务端镜像，默认保留 SQLite 与备份数据卷
  --purge-data 与 uninstall 同时使用，确认后删除全部数据卷

可选环境变量：
  SHANGAN_ENV_FILE  指定部署环境文件，默认使用仓库根目录 .env.deploy
EOF
}

require_runtime() {
  command -v docker >/dev/null 2>&1 || fail "未找到 Docker，请先安装 Docker Engine。"
  docker compose version >/dev/null 2>&1 || fail "未找到 Docker Compose Plugin。"
  docker info >/dev/null 2>&1 || fail "Docker 服务不可用，请先启动 Docker。"
  [[ -f "${COMPOSE_FILE}" ]] || fail "找不到部署文件：${COMPOSE_FILE}"
}

random_hex() {
  local byte_count="$1"
  command -v openssl >/dev/null 2>&1 || fail "首次部署需要 openssl 生成安全随机值。"
  openssl rand -hex "${byte_count}"
}

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

# 首次部署自动创建权限为 600 的环境文件，避免使用固定默认密钥。
create_environment_file() {
  if [[ -f "${ENV_FILE}" ]]; then
    return
  fi
  if docker volume inspect "${PROJECT_NAME}_study-data" >/dev/null 2>&1; then
    fail "检测到既有数据卷但缺少 ${ENV_FILE}，为避免轮换密钥，请先恢复原环境文件。"
  fi

  local jwt_secret
  local playback_secret
  jwt_secret="$(random_hex 32)"
  playback_secret="$(random_hex 32)"
  GENERATED_ADMIN_PASSWORD="$(random_hex 16)"

  umask 077
  {
    printf '# 此文件由部署脚本生成，包含真实密钥，禁止提交到 Git。\n'
    printf 'JWT_SECRET=%s\n' "${jwt_secret}"
    printf 'PLAYBACK_TICKET_SECRET=%s\n' "${playback_secret}"
    printf 'ADMIN_BOOTSTRAP_PASSWORD=%s\n' "${GENERATED_ADMIN_PASSWORD}"
  } >"${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
  log "已生成部署环境文件：${ENV_FILE}"
}

environment_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "${ENV_FILE}" | tail -n 1
}

# 不执行 source，避免把环境文件中的意外内容当作 Shell 命令运行。
validate_environment_file() {
  [[ -f "${ENV_FILE}" ]] || fail "缺少部署环境文件：${ENV_FILE}"
  chmod 600 "${ENV_FILE}"

  local jwt_secret
  local playback_secret
  local admin_password
  jwt_secret="$(environment_value JWT_SECRET)"
  playback_secret="$(environment_value PLAYBACK_TICKET_SECRET)"
  admin_password="$(environment_value ADMIN_BOOTSTRAP_PASSWORD)"

  [[ ${#jwt_secret} -ge 32 ]] || fail "JWT_SECRET 必须至少为 32 字节。"
  [[ ${#playback_secret} -ge 32 ]] || fail "PLAYBACK_TICKET_SECRET 必须至少为 32 字节。"
  [[ -n "${admin_password}" ]] || fail "ADMIN_BOOTSTRAP_PASSWORD 不能为空。"
}

# 等待镜像内置健康检查变为 healthy，失败时输出有限日志帮助定位。
wait_for_health() {
  local container_id=""
  local health=""
  local attempt
  log "等待服务健康检查通过……"
  for ((attempt = 1; attempt <= 60; attempt++)); do
    container_id="$(compose ps -q server 2>/dev/null || true)"
    if [[ -n "${container_id}" ]]; then
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}" 2>/dev/null || true)"
      if [[ "${health}" == "healthy" ]]; then
        log "服务已启动：http://127.0.0.1:18080/actuator/health"
        return
      fi
      if [[ "${health}" == "unhealthy" || "${health}" == "exited" || "${health}" == "dead" ]]; then
        break
      fi
    fi
    sleep 2
  done

  compose logs --tail=200 server >&2 || true
  fail "服务未在规定时间内通过健康检查，当前状态：${health:-unknown}"
}

deploy() {
  create_environment_file
  validate_environment_file
  log "拉取 GitHub Container Registry 最新镜像……"
  compose pull
  log "创建或更新服务……"
  compose up -d --remove-orphans
  wait_for_health
  if [[ -n "${GENERATED_ADMIN_PASSWORD}" ]]; then
    log "首次登录用户名：admin"
    log "首次登录密码：${GENERATED_ADMIN_PASSWORD}"
    log "密码同时保存在 ${ENV_FILE}，请妥善保管。"
  fi
}

update() {
  validate_environment_file
  log "拉取 GitHub Container Registry 最新镜像……"
  compose pull
  log "重建服务并保留 SQLite 数据卷……"
  compose up -d --remove-orphans
  wait_for_health
}

# 环境文件遗失时仍允许停止既有项目；占位值只用于通过 Compose 配置解析。
compose_for_uninstall() {
  if [[ -f "${ENV_FILE}" ]]; then
    compose "$@"
    return
  fi
  env \
    JWT_SECRET=uninstall-only-placeholder-0000000000000000 \
    PLAYBACK_TICKET_SECRET=uninstall-only-placeholder-000000000000 \
    ADMIN_BOOTSTRAP_PASSWORD=uninstall-only-placeholder \
    docker compose -f "${COMPOSE_FILE}" "$@"
}

uninstall() {
  local purge_data="$1"
  if [[ "${purge_data}" == "true" ]]; then
    [[ -t 0 ]] || fail "删除数据必须在交互式终端执行。"
    printf '[上岸部署] 警告：这会永久删除 SQLite、试卷附件和容器内备份。输入 DELETE 继续：'
    local confirmation
    read -r confirmation
    [[ "${confirmation}" == "DELETE" ]] || fail "确认内容不匹配，已取消删除。"
    compose_for_uninstall down --remove-orphans --rmi all --volumes
    log "服务、镜像和数据卷已删除；${ENV_FILE} 仍保留。"
    return
  fi

  compose_for_uninstall down --remove-orphans --rmi all
  log "服务和镜像已删除，SQLite 与备份数据卷已保留。"
}

confirm_regular_uninstall() {
  local confirmation
  printf '[上岸部署] 确认卸载容器和镜像吗？SQLite 与备份数据将保留。[y/N] '
  read -r confirmation
  [[ "${confirmation}" == "y" || "${confirmation}" == "Y" ]]
}

# 无参数运行时进入交互菜单；每次操作完成后返回菜单，便于继续操作或退出。
interactive_menu() {
  [[ -t 0 ]] || fail "当前不是交互式终端，请显式传入 deploy、update 或 uninstall。"
  while true; do
    printf '\n'
    printf '========== 上岸 Docker 管理 ==========\n'
    printf '1. 首次部署 / 重新部署\n'
    printf '2. 更新 GitHub 最新镜像\n'
    printf '3. 卸载服务（保留 SQLite 和备份）\n'
    printf '4. 彻底卸载（永久删除全部数据）\n'
    printf '0. 退出\n'
    printf '请选择操作 [0-4]：'

    local choice
    if ! read -r choice; then
      printf '\n'
      log "已退出。"
      return
    fi
    case "${choice}" in
      1)
        require_runtime
        deploy
        ;;
      2)
        require_runtime
        update
        ;;
      3)
        if confirm_regular_uninstall; then
          require_runtime
          uninstall false
        else
          log "已取消卸载。"
        fi
        ;;
      4)
        require_runtime
        uninstall true
        ;;
      0)
        log "已退出。"
        return
        ;;
      *)
        log "无效选项，请输入 0 到 4。"
        ;;
    esac
  done
}

main() {
  if [[ "$#" -eq 0 ]]; then
    interactive_menu
    return
  fi

  local action="${1:-}"
  local option="${2:-}"
  if [[ "$#" -gt 2 ]]; then
    usage
    exit 2
  fi

  case "${action}" in
    deploy)
      [[ -z "${option}" ]] || fail "deploy 不接受参数。"
      require_runtime
      deploy
      ;;
    update)
      [[ -z "${option}" ]] || fail "update 不接受参数。"
      require_runtime
      update
      ;;
    uninstall)
      require_runtime
      if [[ -z "${option}" ]]; then
        uninstall false
      elif [[ "${option}" == "--purge-data" ]]; then
        uninstall true
      else
        fail "uninstall 仅支持 --purge-data 参数。"
      fi
      ;;
    -h | --help | help)
      usage
      ;;
    *)
      usage
      exit 2
      ;;
  esac
}

main "$@"
