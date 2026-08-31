-- 课程学习内容由管理员导入；历史转写和全局摘要在删除 AI 表前尽量迁入新表。
CREATE TABLE lesson_study_contents (
    id TEXT PRIMARY KEY,
    media_item_id TEXT NOT NULL UNIQUE,
    full_text TEXT NOT NULL,
    summary_markdown TEXT NOT NULL,
    imported_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

-- 按 segment_index 合并历史全文。历史数据可能只有全文或摘要，缺失一侧保留为空，
-- 后续管理员上传的新包仍由应用层强制要求全文和摘要同时非空。
INSERT INTO lesson_study_contents (
    id, media_item_id, full_text, summary_markdown, imported_at, updated_at
)
SELECT
    legacy.media_item_id,
    legacy.media_item_id,
    COALESCE((
        SELECT group_concat(ordered_segments.text, char(10))
        FROM (
            SELECT segment.text
            FROM transcript_segments segment
            WHERE segment.media_item_id = legacy.media_item_id
            ORDER BY segment.segment_index
        ) ordered_segments
    ), ''),
    COALESCE((
        SELECT summary.summary
        FROM video_summaries summary
        WHERE summary.media_item_id = legacy.media_item_id
    ), ''),
    COALESCE(
        (SELECT min(segment.created_at)
         FROM transcript_segments segment
         WHERE segment.media_item_id = legacy.media_item_id),
        (SELECT summary.generated_at
         FROM video_summaries summary
         WHERE summary.media_item_id = legacy.media_item_id),
        0
    ),
    max(
        COALESCE((
            SELECT max(segment.created_at)
            FROM transcript_segments segment
            WHERE segment.media_item_id = legacy.media_item_id
        ), 0),
        COALESCE((
            SELECT summary.generated_at
            FROM video_summaries summary
            WHERE summary.media_item_id = legacy.media_item_id
        ), 0)
    )
FROM (
    SELECT media_item_id FROM transcript_segments
    UNION
    SELECT media_item_id FROM video_summaries
) legacy;

-- 用户已接受删除聊天记录；先删引用表，再删除会话主表。
DROP TABLE ai_messages;
DROP TABLE ai_conversations;

-- 先移除 FTS 同步触发器和虚拟表，再删除历史转写与摘要业务表。
DROP TRIGGER IF EXISTS transcript_segments_fts_insert;
DROP TRIGGER IF EXISTS transcript_segments_fts_delete;
DROP TRIGGER IF EXISTS transcript_segments_fts_update;
DROP TABLE transcript_segments_fts;
DROP TABLE video_section_summaries;
DROP TABLE video_summaries;
DROP TABLE transcript_segments;
DROP TABLE transcription_jobs;

-- SQLite 不支持直接删除列，因此重建运行时配置表，只迁移仍需要的 Emby 字段。
ALTER TABLE runtime_integration_settings RENAME TO runtime_integration_settings_v012;

CREATE TABLE runtime_integration_settings (
    id TEXT PRIMARY KEY CHECK (id = 'default'),
    emby_base_url TEXT NOT NULL,
    emby_api_key TEXT NOT NULL,
    emby_user_id TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

INSERT INTO runtime_integration_settings (
    id, emby_base_url, emby_api_key, emby_user_id, updated_at
)
SELECT id, emby_base_url, emby_api_key, emby_user_id, updated_at
FROM runtime_integration_settings_v012;

DROP TABLE runtime_integration_settings_v012;
