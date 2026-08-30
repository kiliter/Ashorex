#!/usr/bin/env bash
# 上岸 SQLite 在线备份：使用 .backup 保证 WAL 模式下的一致性，并执行完整性校验与保留策略。
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data}"
BACKUP_DIR="${BACKUP_DIR:-/backup}"
STAMP="${STAMP:-$(date -u +%Y%m%d-%H%M%S)}"
DATABASE="${DATA_DIR}/study.db"
DAILY_BACKUP="${BACKUP_DIR}/study-${STAMP}.db"

if [[ ! -f "${DATABASE}" ]]; then
  echo "备份失败：数据库不存在。" >&2
  exit 1
fi
if [[ "${DATABASE}" == *"'"* || "${BACKUP_DIR}" == *"'"* ]]; then
  echo "备份失败：路径不能包含单引号。" >&2
  exit 1
fi
mkdir -p "${BACKUP_DIR}"

create_backup() {
  local target="$1"
  local integrity
  sqlite3 "${DATABASE}" ".backup '${target}'"
  integrity="$(sqlite3 "${target}" "PRAGMA integrity_check;")"
  if [[ "${integrity}" != "ok" ]]; then
    echo "备份失败：完整性校验未返回 ok。" >&2
    exit 1
  fi
}

prune_backups() {
  local pattern="$1"
  local keep="$2"
  local files=()
  local remove_count
  shopt -s nullglob
  files=("${BACKUP_DIR}"/${pattern})
  shopt -u nullglob
  remove_count=$((${#files[@]} - keep))
  if ((remove_count <= 0)); then
    return
  fi
  # 文件名中的 UTC 时间戳可直接按字典序表示新旧顺序。
  IFS=$'\n' files=($(printf '%s\n' "${files[@]}" | LC_ALL=C sort))
  unset IFS
  for ((index = 0; index < remove_count; index++)); do
    rm -- "${files[index]}"
  done
}

create_backup "${DAILY_BACKUP}"

# 每周日生成一份独立周备份；Smoke Test 可通过 CREATE_WEEKLY=1 确定性覆盖该分支。
if [[ "${CREATE_WEEKLY:-0}" == "1" || "$(date -u +%u)" == "7" ]]; then
  create_backup "${BACKUP_DIR}/weekly-study-${STAMP}.db"
fi

prune_backups "study-[0-9]*.db" 7
prune_backups "weekly-study-[0-9]*.db" 4
echo "备份完成：${DAILY_BACKUP}"
