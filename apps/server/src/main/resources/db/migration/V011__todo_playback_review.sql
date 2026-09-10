-- 复习只重置 Todo 播放轮次，课时累计和完成历史保留。
ALTER TABLE todos ADD COLUMN playback_epoch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE todos ADD COLUMN playback_resume_ms INTEGER NOT NULL DEFAULT 0;
ALTER TABLE todos ADD COLUMN playback_resume_seq INTEGER NOT NULL DEFAULT -1;
ALTER TABLE todos ADD COLUMN playback_reset_id TEXT;
