-- Emby 媒体库重建后 Item ID 可能变化；本地 media_items.id 继续作为不可变业务身份。
ALTER TABLE media_items ADD COLUMN emby_item_type TEXT NOT NULL DEFAULT 'Video';
ALTER TABLE media_items ADD COLUMN source_fingerprint TEXT;

-- 同一物理媒体来源只允许对应一个本地课时；历史行暂时为空并在后续同步时补齐。
CREATE UNIQUE INDEX idx_media_items_source_fingerprint
    ON media_items(source_fingerprint)
    WHERE source_fingerprint IS NOT NULL;

-- 审计只记录外部 Item ID 的变化与匹配方式，禁止保存 Emby 原始媒体路径。
CREATE TABLE media_item_source_mappings (
    id TEXT PRIMARY KEY,
    media_item_id TEXT NOT NULL,
    old_emby_item_id TEXT NOT NULL,
    new_emby_item_id TEXT NOT NULL,
    match_type TEXT NOT NULL CHECK (
        match_type IN ('SOURCE_FINGERPRINT', 'UNIQUE_LEGACY_METADATA', 'ADMIN_CONFIRMED')
    ),
    mapped_at INTEGER NOT NULL,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE INDEX idx_media_item_source_mappings_item_time
    ON media_item_source_mappings(media_item_id, mapped_at);
