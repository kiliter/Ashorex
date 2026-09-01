-- 所有用户按视频内容进度百分比设置验活间隔，默认每推进 50% 验活一次。
ALTER TABLE users ADD COLUMN alive_check_interval_percent INTEGER NOT NULL DEFAULT 50
    CHECK (alive_check_interval_percent BETWEEN 1 AND 50);

-- 会话持久化下一次验活的绝对视频位置；旧观看时长阈值保留但不再读取。
ALTER TABLE watch_sessions ADD COLUMN alive_check_due_position_ms INTEGER
    CHECK (alive_check_due_position_ms IS NULL OR alive_check_due_position_ms >= 0);
