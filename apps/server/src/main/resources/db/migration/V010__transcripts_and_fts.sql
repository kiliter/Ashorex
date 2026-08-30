-- 视频转写任务、时间戳文本、分段摘要与全局摘要。
CREATE TABLE transcription_jobs (
    id TEXT PRIMARY KEY,
    media_item_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'EXTRACTING_AUDIO', 'TRANSCRIBING', 'SUMMARIZING', 'READY', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error TEXT,
    started_at INTEGER,
    finished_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

-- 表达式部分唯一索引确保小型部署全局同时只有一个活动任务。
CREATE UNIQUE INDEX uq_transcription_jobs_one_active
    ON transcription_jobs ((1))
    WHERE status IN ('PENDING', 'EXTRACTING_AUDIO', 'TRANSCRIBING', 'SUMMARIZING');

CREATE TABLE transcript_segments (
    id TEXT PRIMARY KEY,
    media_item_id TEXT NOT NULL,
    segment_index INTEGER NOT NULL CHECK (segment_index >= 0),
    start_ms INTEGER NOT NULL CHECK (start_ms >= 0),
    end_ms INTEGER NOT NULL CHECK (end_ms >= start_ms),
    text TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (media_item_id, segment_index),
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE INDEX idx_transcript_segments_media_time
    ON transcript_segments (media_item_id, start_ms, end_ms);

-- 外部内容 FTS 表不复制业务主键，通过 rowid 与主表同步。
CREATE VIRTUAL TABLE transcript_segments_fts USING fts5(
    text,
    content='transcript_segments',
    content_rowid='rowid',
    -- trigram 支持中文连续短语检索，无需依赖空格分词。
    tokenize='trigram'
);

CREATE TRIGGER transcript_segments_fts_insert AFTER INSERT ON transcript_segments BEGIN
    INSERT INTO transcript_segments_fts(rowid, text) VALUES (new.rowid, new.text);
END;

CREATE TRIGGER transcript_segments_fts_delete AFTER DELETE ON transcript_segments BEGIN
    INSERT INTO transcript_segments_fts(transcript_segments_fts, rowid, text)
    VALUES ('delete', old.rowid, old.text);
END;

CREATE TRIGGER transcript_segments_fts_update AFTER UPDATE ON transcript_segments BEGIN
    INSERT INTO transcript_segments_fts(transcript_segments_fts, rowid, text)
    VALUES ('delete', old.rowid, old.text);
    INSERT INTO transcript_segments_fts(rowid, text) VALUES (new.rowid, new.text);
END;

CREATE TABLE video_section_summaries (
    id TEXT PRIMARY KEY,
    media_item_id TEXT NOT NULL,
    section_index INTEGER NOT NULL CHECK (section_index >= 0),
    start_ms INTEGER NOT NULL CHECK (start_ms >= 0),
    end_ms INTEGER NOT NULL CHECK (end_ms >= start_ms),
    summary TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (media_item_id, section_index),
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE TABLE video_summaries (
    id TEXT PRIMARY KEY,
    media_item_id TEXT NOT NULL UNIQUE,
    summary TEXT NOT NULL,
    outline_json TEXT NOT NULL DEFAULT '[]',
    model_name TEXT NOT NULL,
    generated_at INTEGER NOT NULL,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);
