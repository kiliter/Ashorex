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
ATTACHMENTS_DIR="${DATA_DIR}/mock-exams"
ARCHIVE_DIR="${DATA_DIR}/pre-restore-${STAMP}"
RESTORE_TEMP="${DATA_DIR}/.study-restore-${STAMP}.db"
ATTACHMENTS_BACKUP="${BACKUP_FILE%.db}.attachments.tar.gz"
ATTACHMENTS_CHECKSUM="${ATTACHMENTS_BACKUP}.sha256"
ATTACHMENTS_TEMP="${DATA_DIR}/.mock-exams-restore-${STAMP}"

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

attachment_rows=0
if [[ "$(sqlite3 "${BACKUP_FILE}" "select count(*) from sqlite_master where type='table' and name='mock_exam_attachments';")" == "1" ]]; then
  attachment_rows="$(sqlite3 "${BACKUP_FILE}" "select count(*) from mock_exam_attachments;")"
fi
if [[ -f "${ATTACHMENTS_BACKUP}" && -f "${ATTACHMENTS_CHECKSUM}" ]]; then
  (
    cd "$(dirname "${ATTACHMENTS_BACKUP}")"
    sha256sum -c "$(basename "${ATTACHMENTS_CHECKSUM}")"
  )
  # 备份由本服务生成，仍限制归档内所有路径只能位于 mock-exams 目录。
  while IFS= read -r entry; do
    if [[ "${entry}" != "mock-exams" && "${entry}" != "mock-exams/"* ]]; then
      echo "恢复失败：附件归档包含越界路径。" >&2
      exit 1
    fi
  done < <(tar -tzf "${ATTACHMENTS_BACKUP}")
elif ((attachment_rows > 0)); then
  echo "恢复失败：数据库备份包含试卷附件记录，但缺少附件归档或校验文件。" >&2
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
if [[ -d "${ATTACHMENTS_DIR}" ]]; then
  mv -- "${ATTACHMENTS_DIR}" "${ARCHIVE_DIR}/mock-exams"
fi

sqlite3 "${RESTORE_TEMP}" ".restore '${BACKUP_FILE}'"
if [[ "$(sqlite3 "${RESTORE_TEMP}" "PRAGMA integrity_check;")" != "ok" ]]; then
  echo "恢复失败：恢复后的数据库校验未返回 ok。" >&2
  exit 1
fi
mv -- "${RESTORE_TEMP}" "${DATABASE}"
if [[ -f "${ATTACHMENTS_BACKUP}" ]]; then
  mkdir -p "${ATTACHMENTS_TEMP}"
  tar -xzf "${ATTACHMENTS_BACKUP}" -C "${ATTACHMENTS_TEMP}"
  mv -- "${ATTACHMENTS_TEMP}/mock-exams" "${ATTACHMENTS_DIR}"
  rmdir "${ATTACHMENTS_TEMP}"
else
  mkdir -p "${ATTACHMENTS_DIR}"
fi
echo "恢复完成；原数据库已归档到：${ARCHIVE_DIR}"
