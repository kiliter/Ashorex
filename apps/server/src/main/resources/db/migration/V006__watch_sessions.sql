-- 播放选择与可信观看会话；Task 9 在此表基础上实现心跳和验活。
CREATE TABLE watch_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    media_item_id TEXT NOT NULL,
    emby_item_id TEXT NOT NULL,
    plan_item_id TEXT,
    device_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE','PAUSED','COMPLETED','STOPPED','EXPIRED')),
    play_session_id TEXT NOT NULL,
    upstream_path TEXT NOT NULL,
    hls INTEGER NOT NULL CHECK (hls IN (0,1)),
    duration_ms INTEGER NOT NULL CHECK (duration_ms > 0),
    started_position_ms INTEGER NOT NULL DEFAULT 0,
    last_reported_position_ms INTEGER NOT NULL DEFAULT 0,
    max_verified_position_ms INTEGER NOT NULL DEFAULT 0,
    verified_watch_ms INTEGER NOT NULL DEFAULT 0,
    last_sequence INTEGER NOT NULL DEFAULT 0,
    last_heartbeat_at INTEGER NOT NULL,
    alive_check_due_watch_ms INTEGER,
    alive_check_pending INTEGER NOT NULL DEFAULT 0 CHECK (alive_check_pending IN (0,1)),
    started_at INTEGER NOT NULL,
    ended_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT,
    FOREIGN KEY (plan_item_id) REFERENCES daily_plan_items(id) ON DELETE RESTRICT
);

CREATE INDEX idx_watch_sessions_user_status ON watch_sessions(user_id,status);
CREATE INDEX idx_watch_sessions_plan_status ON watch_sessions(plan_item_id,status);
