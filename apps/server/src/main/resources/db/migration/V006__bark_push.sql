-- 个人 Bark 配置随用户删除，不使用全局设备密钥。
CREATE TABLE user_bark_settings (
 user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 base_url TEXT NOT NULL DEFAULT 'https://api.day.app',
 device_key TEXT NOT NULL DEFAULT '',
 enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0, 1))
);
-- 扩展投递子表渠道约束，保留所有历史流水。
CREATE TABLE nag_deliveries_bark (
 id TEXT PRIMARY KEY, nag_id TEXT NOT NULL REFERENCES nags(id) ON DELETE CASCADE,
 channel TEXT NOT NULL CHECK(channel IN ('FULLSCREEN', 'SERVERCHAN', 'BARK')),
 status TEXT NOT NULL CHECK(status IN ('SENT', 'SHOWN', 'FAILED')),
 detail TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL
);
INSERT INTO nag_deliveries_bark SELECT * FROM nag_deliveries;
DROP TABLE nag_deliveries;
ALTER TABLE nag_deliveries_bark RENAME TO nag_deliveries;
CREATE INDEX idx_nag_deliveries_nag ON nag_deliveries(nag_id, created_at);

-- 系统异常目的地独立于用户配置。
ALTER TABLE runtime_settings ADD COLUMN bark_base_url TEXT NOT NULL DEFAULT 'https://api.day.app';
ALTER TABLE runtime_settings ADD COLUMN bark_device_key TEXT NOT NULL DEFAULT '';
ALTER TABLE runtime_settings ADD COLUMN bark_enabled INTEGER NOT NULL DEFAULT 0 CHECK(bark_enabled IN (0, 1));
ALTER TABLE runtime_settings ADD COLUMN bark_timeout_seconds INTEGER NOT NULL DEFAULT 8;
