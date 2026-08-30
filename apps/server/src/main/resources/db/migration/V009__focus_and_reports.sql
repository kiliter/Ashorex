-- 专注计时与确定性日报快照。
CREATE TABLE focus_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    plan_item_id TEXT,
    media_item_id TEXT,
    focus_type TEXT NOT NULL CHECK (focus_type IN ('POMODORO','PRACTICE','MOCK_EXAM')),
    status TEXT NOT NULL CHECK (status IN ('RUNNING','PAUSED','FINISHED','CANCELLED')),
    planned_seconds INTEGER NOT NULL CHECK (planned_seconds > 0),
    actual_seconds INTEGER NOT NULL DEFAULT 0 CHECK (actual_seconds >= 0),
    started_at INTEGER NOT NULL,
    running_since INTEGER,
    paused_at INTEGER,
    ended_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (plan_item_id) REFERENCES daily_plan_items(id) ON DELETE RESTRICT,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

-- 一个用户同时只能有一个运行中或暂停中的专注会话。
CREATE UNIQUE INDEX uq_focus_sessions_user_active
    ON focus_sessions(user_id)
    WHERE status IN ('RUNNING','PAUSED');
CREATE INDEX idx_focus_sessions_plan_status
    ON focus_sessions(plan_item_id,status);
CREATE INDEX idx_focus_sessions_user_started
    ON focus_sessions(user_id,started_at);

CREATE TABLE daily_reports (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    report_date TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    judgment_text TEXT NOT NULL,
    generated_at INTEGER NOT NULL,
    UNIQUE (user_id,report_date),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_daily_reports_user_date
    ON daily_reports(user_id,report_date);
