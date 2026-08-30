#!/usr/bin/env bash
# 上岸 SQLite 恢复：要求服务已停止，先校验备份，再归档现库并原子替换。
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "用法：SERVICE_STOPPED=1 restore.sh /backup/study-时间戳.db" >&2
  exit 1
fi
if [[ "${SERVICE_STOPPED:-0}" != "1" ]]; then
  echo "恢复失败：请先停止上岸服务，再显式设置 SERVICE_STOPPED=1。" >&2
  exit 1
fi

DATA_DIR="${DATA_DIR:-/data}"
BACKUP_FILE="$1"
STAMP="${STAMP:-$(date -u +%Y%m%d-%H%M%S)}"
DATABASE="${DATA_DIR}/study.db"
ARCHIVE_DIR="${DATA_DIR}/pre-restore-${STAMP}"
RESTORE_TEMP="${DATA_DIR}/.study-restore-${STAMP}.db"

if [[ ! -f "${BACKUP_FILE}" ]]; then
  echo "恢复失败：备份文件不存在。" >&2
  exit 1
fi
if [[ "${BACKUP_FILE}" == *"'"* || "${RESTORE_TEMP}" == *"'"* ]]; then
  echo "恢复失败：路径不能包含单引号。" >&2
  exit 1
fi
if [[ "$(sqlite3 "${BACKUP_FILE}" "PRAGMA integrity_check;")" != "ok" ]]; then
  echo "恢复失败：备份完整性校验未返回 ok。" >&2
  exit 1
fi

mkdir -p "${DATA_DIR}" "${ARCHIVE_DIR}"
if [[ -f "${DATABASE}" ]]; then
  mv -- "${DATABASE}" "${ARCHIVE_DIR}/study.db"
fi
for suffix in -wal -shm; do
  if [[ -f "${DATABASE}${suffix}" ]]; then
    mv -- "${DATABASE}${suffix}" "${ARCHIVE_DIR}/study.db${suffix}"
  fi
done

sqlite3 "${RESTORE_TEMP}" ".restore '${BACKUP_FILE}'"
if [[ "$(sqlite3 "${RESTORE_TEMP}" "PRAGMA integrity_check;")" != "ok" ]]; then
  echo "恢复失败：恢复后的数据库校验未返回 ok。" >&2
  exit 1
fi
mv -- "${RESTORE_TEMP}" "${DATABASE}"
echo "恢复完成；原数据库已归档到：${ARCHIVE_DIR}"
