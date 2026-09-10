-- 用户手动上报的本机诊断日志台账；文件落在 DATA_DIR/diagnostics/{userId}/。
CREATE TABLE diagnostic_log_uploads (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users (id),
    storage_path TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    app_version TEXT NOT NULL DEFAULT '',
    platform TEXT NOT NULL DEFAULT '',
    uploaded_at INTEGER NOT NULL
);

CREATE INDEX idx_diagnostic_log_uploads_user_uploaded
    ON diagnostic_log_uploads (user_id, uploaded_at DESC);
