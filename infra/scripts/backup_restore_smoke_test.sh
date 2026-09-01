#!/usr/bin/env bash
# 使用临时 WAL 数据库验证在线备份、保留策略、独立恢复、Schema 版本和代表性业务行。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d)"
SOURCE_DIR="${TEST_ROOT}/source"
BACKUP_DIR="${TEST_ROOT}/backup"
RESTORE_DIR="${TEST_ROOT}/restore"
FIFO="${TEST_ROOT}/sqlite-input"
SQLITE_PID=""

cleanup() {
  if [[ -n "${SQLITE_PID}" ]]; then
    kill "${SQLITE_PID}" 2>/dev/null || true
  fi
  rm -rf -- "${TEST_ROOT}"
}
trap cleanup EXIT
mkdir -p "${SOURCE_DIR}" "${BACKUP_DIR}" "${RESTORE_DIR}"
mkdir -p "${SOURCE_DIR}/mock-exams/session-1"
printf 'mock-exam-paper' >"${SOURCE_DIR}/mock-exams/session-1/paper.png"

sqlite3 "${SOURCE_DIR}/study.db" <<'SQL'
PRAGMA journal_mode=WAL;
CREATE TABLE flyway_schema_history (installed_rank INTEGER, version TEXT, success INTEGER);
CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT);
CREATE TABLE courses (id TEXT PRIMARY KEY, name TEXT);
CREATE TABLE daily_plans (id TEXT PRIMARY KEY, status TEXT);
CREATE TABLE video_progress (user_id TEXT, media_item_id TEXT, max_verified_position_ms INTEGER);
CREATE TABLE learning_debts (id TEXT PRIMARY KEY, status TEXT);
CREATE TABLE lesson_study_contents (id TEXT PRIMARY KEY, media_item_id TEXT, full_text TEXT);
CREATE TABLE mock_exam_attachments (id TEXT PRIMARY KEY, storage_path TEXT);
INSERT INTO flyway_schema_history VALUES (13, '013', 1);
INSERT INTO users VALUES ('user-1', 'tester');
INSERT INTO courses VALUES ('course-1', '测试课程');
INSERT INTO daily_plans VALUES ('plan-1', 'LOCKED');
INSERT INTO video_progress VALUES ('user-1', 'lesson-1', 120000);
INSERT INTO learning_debts VALUES ('debt-1', 'OPEN');
INSERT INTO lesson_study_contents VALUES ('content-1', 'lesson-1', '测试全文');
INSERT INTO mock_exam_attachments VALUES ('attachment-1', 'session-1/paper.png');
SQL

# 保持一个读连接存活，证明 .backup 不要求服务数据库关闭。
mkfifo "${FIFO}"
sqlite3 "${SOURCE_DIR}/study.db" <"${FIFO}" &
SQLITE_PID=$!
exec 3>"${FIFO}"
printf 'BEGIN; SELECT count(*) FROM users;\n' >&3

DATA_DIR="${SOURCE_DIR}" BACKUP_DIR="${BACKUP_DIR}" STAMP="20260830-010101" CREATE_WEEKLY=1 \
  "${SCRIPT_DIR}/backup.sh"

printf '.quit\n' >&3
exec 3>&-
wait "${SQLITE_PID}"
SQLITE_PID=""

# 恢复前创建代表旧实例的数据库，验证它会被归档而不是静默覆盖。
sqlite3 "${RESTORE_DIR}/study.db" "CREATE TABLE old_instance(id INTEGER);"
mkdir -p "${RESTORE_DIR}/mock-exams/old-session"
printf 'old-paper' >"${RESTORE_DIR}/mock-exams/old-session/old.png"
SERVICE_STOPPED=1 DATA_DIR="${RESTORE_DIR}" STAMP="20260830-020202" \
  "${SCRIPT_DIR}/restore.sh" "${BACKUP_DIR}/study-20260830-010101.db"

assert_scalar() {
  local sql="$1"
  local expected="$2"
  local actual
  actual="$(sqlite3 "${RESTORE_DIR}/study.db" "${sql}")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "Smoke Test 失败：期望 ${expected}，实际 ${actual}。" >&2
    exit 1
  fi
}

assert_scalar "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;" "013"
for table in users courses daily_plans video_progress learning_debts lesson_study_contents mock_exam_attachments; do
  assert_scalar "SELECT count(*) FROM ${table};" "1"
done
assert_scalar "PRAGMA integrity_check;" "ok"
test -f "${RESTORE_DIR}/pre-restore-20260830-020202/study.db"
test -f "${RESTORE_DIR}/pre-restore-20260830-020202/mock-exams/old-session/old.png"
test "$(cat "${RESTORE_DIR}/mock-exams/session-1/paper.png")" = "mock-exam-paper"
test -f "${BACKUP_DIR}/weekly-study-20260830-010101.db"
test -f "${BACKUP_DIR}/study-20260830-010101.attachments.tar.gz.sha256"
test -f "${BACKUP_DIR}/weekly-study-20260830-010101.attachments.tar.gz"
echo "备份恢复 Smoke Test 通过。"
