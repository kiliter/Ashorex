-- SQLite 扩展 CHECK 约束需重建表；先关闭当前连接外键，事务内原样复制数据。
-- Flyway 脚本配置关闭外层事务，确保 PRAGMA 在 BEGIN 之前生效。
CREATE TABLE todos_focus_v3 (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    local_date TEXT NOT NULL,
    todo_type TEXT NOT NULL CHECK (todo_type IN ('COURSE', 'FOCUS', 'TASK')),
    title TEXT NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    sort_order INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),

    -- 课程类
    resource_id TEXT,
    target_progress_permille INTEGER
        CHECK (target_progress_permille IS NULL
               OR (target_progress_permille BETWEEN 1 AND 1000)),
    progress_position_ms INTEGER NOT NULL DEFAULT 0 CHECK (progress_position_ms >= 0),
    progress_page INTEGER NOT NULL DEFAULT 0 CHECK (progress_page >= 0),
    watched_ms INTEGER NOT NULL DEFAULT 0 CHECK (watched_ms >= 0),

    -- 专注类
    planned_seconds INTEGER
        CHECK (planned_seconds IS NULL OR (planned_seconds BETWEEN 60 AND 43200)),
    focus_state TEXT NOT NULL DEFAULT 'IDLE'
        CHECK (focus_state IN ('IDLE', 'RUNNING', 'PAUSED', 'STOPPED', 'FINISHED', 'ABANDONED')),
    focus_started_at INTEGER,
    focused_ms INTEGER NOT NULL DEFAULT 0 CHECK (focused_ms >= 0),

    -- 全类型
    require_evidence INTEGER NOT NULL DEFAULT 0 CHECK (require_evidence IN (0, 1)),
    completed_at INTEGER,
    backfilled INTEGER NOT NULL DEFAULT 0 CHECK (backfilled IN (0, 1)),
    backfill_note TEXT,
    supervisor_user_id_snapshot TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,

    CHECK (todo_type <> 'COURSE'
           OR (resource_id IS NOT NULL AND target_progress_permille IS NOT NULL)),
    CHECK (todo_type <> 'FOCUS' OR planned_seconds IS NOT NULL),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id) REFERENCES learning_resources(id) ON DELETE RESTRICT
);

INSERT INTO todos_focus_v3 SELECT * FROM todos;
DROP TABLE todos;
ALTER TABLE todos_focus_v3 RENAME TO todos;
CREATE INDEX idx_todos_user_date ON todos(user_id, local_date, sort_order, id);
CREATE INDEX idx_todos_user_status ON todos(user_id, status, local_date);
CREATE INDEX idx_todos_resource ON todos(resource_id);
CREATE UNIQUE INDEX uq_todos_running_focus
    ON todos(user_id)
    WHERE focus_state = 'RUNNING';

-- 历史累计不清零，单独记录新一轮的累计起点。
ALTER TABLE todos ADD COLUMN focus_attempt_base_ms INTEGER NOT NULL DEFAULT 0 CHECK (focus_attempt_base_ms >= 0);
-- 专注操作复用进度流水，空值代表历史记录，不伪造历史操作。
ALTER TABLE todo_progress_events ADD COLUMN focus_action TEXT;
ALTER TABLE todo_progress_events ADD COLUMN focus_from_state TEXT;
ALTER TABLE todo_progress_events ADD COLUMN focus_to_state TEXT;
ALTER TABLE todo_progress_events ADD COLUMN focus_total_ms INTEGER;
ALTER TABLE todo_progress_events ADD COLUMN focus_request_id TEXT;
CREATE UNIQUE INDEX uq_focus_request ON todo_progress_events(todo_id, focus_request_id)
    WHERE focus_request_id IS NOT NULL;
