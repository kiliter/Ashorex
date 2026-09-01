-- V1.3 今日作战单采用版本化完整快照；旧 status/item_type 字段保留用于兼容历史代码和数据。
ALTER TABLE daily_plans ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE daily_plans ADD COLUMN lifecycle_status TEXT NOT NULL DEFAULT 'DRAFT';
ALTER TABLE daily_plans ADD COLUMN activated_at INTEGER;

UPDATE daily_plans
SET lifecycle_status = CASE
    WHEN status IN ('DRAFT', 'LOCKED') THEN CASE WHEN status = 'LOCKED' THEN 'ACTIVE' ELSE 'DRAFT' END
    ELSE status
END;

ALTER TABLE daily_plan_items ADD COLUMN item_kind TEXT;
ALTER TABLE daily_plan_items ADD COLUMN mock_exam_preset_id TEXT;
ALTER TABLE daily_plan_items ADD COLUMN mock_exam_name_snapshot TEXT;

UPDATE daily_plan_items SET item_kind = item_type WHERE item_kind IS NULL;

CREATE TABLE daily_plan_revisions (
    id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    items_snapshot_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (plan_id, version),
    FOREIGN KEY (plan_id) REFERENCES daily_plans(id) ON DELETE CASCADE
);

CREATE TABLE mock_exam_presets (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL CHECK (duration_seconds BETWEEN 60 AND 43200),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE mock_exam_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    plan_item_id TEXT NOT NULL UNIQUE,
    name_snapshot TEXT NOT NULL,
    duration_seconds_snapshot INTEGER NOT NULL CHECK (duration_seconds_snapshot > 0),
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'AWAITING_UPLOAD', 'COMPLETED', 'CANCELLED')),
    started_at INTEGER NOT NULL,
    deadline_at INTEGER NOT NULL,
    submitted_at INTEGER,
    completed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (plan_item_id) REFERENCES daily_plan_items(id) ON DELETE RESTRICT
);

CREATE TABLE mock_exam_attachments (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    storage_path TEXT NOT NULL UNIQUE,
    original_filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    sha256 TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES mock_exam_sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE lesson_review_events (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    media_item_id TEXT NOT NULL,
    watch_session_id TEXT NOT NULL UNIQUE,
    reviewed_on TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT,
    FOREIGN KEY (watch_session_id) REFERENCES watch_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_plan_revisions_plan_version ON daily_plan_revisions(plan_id, version);
CREATE INDEX idx_mock_exam_presets_user_sort ON mock_exam_presets(user_id, sort_order, id);
CREATE INDEX idx_mock_exam_sessions_user_status ON mock_exam_sessions(user_id, status, deadline_at);
CREATE INDEX idx_mock_exam_attachments_session_sort ON mock_exam_attachments(session_id, sort_order, id);
CREATE INDEX idx_review_events_user_date ON lesson_review_events(user_id, reviewed_on, created_at);
