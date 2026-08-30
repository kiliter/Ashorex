-- 用户维度的视频可信进度与验活结果；观看会话仅保存聚合值，不保存每个心跳事件。
CREATE TABLE video_progress (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    media_item_id TEXT NOT NULL,
    max_verified_position_ms INTEGER NOT NULL DEFAULT 0 CHECK (max_verified_position_ms >= 0),
    verified_watch_ms INTEGER NOT NULL DEFAULT 0 CHECK (verified_watch_ms >= 0),
    completed_at INTEGER,
    last_watched_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (user_id, media_item_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE TABLE alive_checks (
    id TEXT PRIMARY KEY,
    watch_session_id TEXT NOT NULL,
    required_at INTEGER NOT NULL,
    responded_at INTEGER,
    status TEXT CHECK (status IN ('PASSED', 'FAILED')),
    created_at INTEGER NOT NULL,
    FOREIGN KEY (watch_session_id) REFERENCES watch_sessions(id) ON DELETE CASCADE
);

-- 已同步可信时间用于关闭会话时只补记尚未同步的增量，避免重复累计。
ALTER TABLE watch_sessions
    ADD COLUMN synced_verified_watch_ms INTEGER NOT NULL DEFAULT 0
        CHECK (synced_verified_watch_ms >= 0);

CREATE INDEX idx_video_progress_user_completed
    ON video_progress(user_id, completed_at);
CREATE INDEX idx_alive_checks_session_required
    ON alive_checks(watch_session_id, required_at);
