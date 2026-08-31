-- 允许全文和摘要分别就绪；V013 的空字符串按缺失内容转换为 NULL。
ALTER TABLE lesson_study_contents RENAME TO lesson_study_contents_v013;

CREATE TABLE lesson_study_contents (
    id TEXT PRIMARY KEY,
    media_item_id TEXT NOT NULL UNIQUE,
    full_text TEXT,
    summary_markdown TEXT,
    transcript_updated_at INTEGER,
    summary_updated_at INTEGER,
    imported_at INTEGER,
    updated_at INTEGER NOT NULL,
    CHECK (full_text IS NOT NULL OR summary_markdown IS NOT NULL),
    CHECK (full_text IS NULL OR length(trim(full_text)) > 0),
    CHECK (summary_markdown IS NULL OR length(trim(summary_markdown)) > 0),
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

INSERT INTO lesson_study_contents (
    id, media_item_id, full_text, summary_markdown,
    transcript_updated_at, summary_updated_at, imported_at, updated_at
)
SELECT
    id,
    media_item_id,
    NULLIF(trim(full_text), ''),
    NULLIF(trim(summary_markdown), ''),
    CASE WHEN length(trim(full_text)) > 0 THEN updated_at END,
    CASE WHEN length(trim(summary_markdown)) > 0 THEN updated_at END,
    imported_at,
    updated_at
FROM lesson_study_contents_v013
WHERE length(trim(full_text)) > 0 OR length(trim(summary_markdown)) > 0;

DROP TABLE lesson_study_contents_v013;

-- 外部服务使用单行原子快照；旧 Emby 配置完整保留，其余字段使用安全默认值。
ALTER TABLE runtime_integration_settings RENAME TO runtime_integration_settings_v013;

CREATE TABLE runtime_integration_settings (
    id TEXT PRIMARY KEY CHECK (id = 'default'),
    emby_base_url TEXT NOT NULL,
    emby_api_key TEXT NOT NULL,
    emby_user_id TEXT NOT NULL,
    asr_base_url TEXT NOT NULL,
    asr_api_key TEXT NOT NULL,
    asr_model TEXT NOT NULL,
    asr_language TEXT NOT NULL,
    asr_chunk_duration_seconds INTEGER NOT NULL CHECK (asr_chunk_duration_seconds BETWEEN 5 AND 600),
    asr_timeout_seconds INTEGER NOT NULL CHECK (asr_timeout_seconds BETWEEN 1 AND 7200),
    llm_base_url TEXT NOT NULL,
    llm_api_key TEXT NOT NULL,
    llm_model TEXT NOT NULL,
    llm_context_length INTEGER NOT NULL CHECK (llm_context_length BETWEEN 4096 AND 2000000),
    llm_max_completion_tokens INTEGER NOT NULL CHECK (llm_max_completion_tokens BETWEEN 256 AND 200000),
    llm_timeout_seconds INTEGER NOT NULL CHECK (llm_timeout_seconds BETWEEN 1 AND 1800),
    openrouter_api_key TEXT NOT NULL,
    content_auto_fill_enabled INTEGER NOT NULL CHECK (content_auto_fill_enabled IN (0, 1)),
    content_auto_fill_interval_minutes INTEGER NOT NULL CHECK (content_auto_fill_interval_minutes BETWEEN 1 AND 1440),
    updated_at INTEGER NOT NULL
);

INSERT INTO runtime_integration_settings (
    id, emby_base_url, emby_api_key, emby_user_id,
    asr_base_url, asr_api_key, asr_model, asr_language,
    asr_chunk_duration_seconds, asr_timeout_seconds,
    llm_base_url, llm_api_key, llm_model, llm_context_length,
    llm_max_completion_tokens, llm_timeout_seconds,
    openrouter_api_key, content_auto_fill_enabled,
    content_auto_fill_interval_minutes, updated_at
)
SELECT
    id, emby_base_url, emby_api_key, emby_user_id,
    '', '', 'mlx-community/Qwen3-ASR-1.7B-8bit', 'Chinese',
    30, 1800,
    '', '', '', 131072, 8192, 300,
    '', 0, 15, updated_at
FROM runtime_integration_settings_v013;

DROP TABLE runtime_integration_settings_v013;

-- 内容生成任务持久化全部阶段和安全的模型快照，外部调用期间不持有事务。
CREATE TABLE content_generation_jobs (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL,
    media_item_id TEXT NOT NULL,
    job_type TEXT NOT NULL CHECK (job_type IN ('TRANSCRIBE', 'SUMMARIZE', 'GENERATE_QUIZ')),
    status TEXT NOT NULL CHECK (status IN (
        'QUEUED', 'FETCHING_AUDIO', 'TRANSCRIBING', 'SUMMARIZING',
        'GENERATING_QUIZ', 'READY', 'READY_FOR_REVIEW', 'FAILED'
    )),
    requested_question_count INTEGER NOT NULL DEFAULT 5 CHECK (requested_question_count BETWEEN 1 AND 20),
    overwrite_existing INTEGER NOT NULL DEFAULT 0 CHECK (overwrite_existing IN (0, 1)),
    queued_at INTEGER NOT NULL,
    started_at INTEGER,
    finished_at INTEGER,
    audio_duration_ms INTEGER,
    fetch_ms INTEGER,
    transcribe_ms INTEGER,
    summarize_ms INTEGER,
    quiz_generate_ms INTEGER,
    total_ms INTEGER,
    asr_model TEXT,
    llm_model TEXT,
    llm_context_length INTEGER,
    llm_max_completion_tokens INTEGER,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    error_code TEXT,
    error_message TEXT,
    created_by TEXT NOT NULL,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE INDEX idx_content_jobs_queue
    ON content_generation_jobs(status, queued_at, course_id, media_item_id);
CREATE INDEX idx_content_jobs_course
    ON content_generation_jobs(course_id, queued_at DESC);
CREATE UNIQUE INDEX uq_content_jobs_active_lesson_type
    ON content_generation_jobs(media_item_id, job_type)
    WHERE status IN (
        'QUEUED', 'FETCHING_AUDIO', 'TRANSCRIBING', 'SUMMARIZING', 'GENERATING_QUIZ'
    );

CREATE TABLE content_generation_job_logs (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL,
    occurred_at INTEGER NOT NULL,
    level TEXT NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR')),
    stage TEXT NOT NULL,
    message TEXT NOT NULL,
    FOREIGN KEY (job_id) REFERENCES content_generation_jobs(id) ON DELETE CASCADE
);

CREATE INDEX idx_content_job_logs_job_time
    ON content_generation_job_logs(job_id, occurred_at, id);

-- OpenRouter 只提供模型元数据；实际推理地址仍由 LLM Base URL 决定。
CREATE TABLE llm_model_catalog (
    model_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    context_length INTEGER NOT NULL CHECK (context_length > 0),
    max_completion_tokens INTEGER NOT NULL CHECK (max_completion_tokens > 0),
    tokenizer TEXT NOT NULL,
    supported_parameters_json TEXT NOT NULL,
    fetched_at INTEGER NOT NULL,
    active INTEGER NOT NULL CHECK (active IN (0, 1))
);

CREATE INDEX idx_llm_model_catalog_active_name
    ON llm_model_catalog(active, display_name, model_id);

-- AI 题目先进入独立草稿；只有管理员发布后才写入正式题库。
CREATE TABLE quiz_generation_drafts (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL UNIQUE,
    course_id TEXT NOT NULL,
    media_item_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('READY_FOR_REVIEW', 'PUBLISHED')),
    requested_question_count INTEGER NOT NULL CHECK (requested_question_count BETWEEN 1 AND 20),
    created_at INTEGER NOT NULL,
    published_at INTEGER,
    FOREIGN KEY (job_id) REFERENCES content_generation_jobs(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE INDEX idx_quiz_drafts_course_status
    ON quiz_generation_drafts(course_id, status, created_at DESC);

CREATE TABLE quiz_generation_draft_items (
    id TEXT PRIMARY KEY,
    draft_id TEXT NOT NULL,
    question_type TEXT NOT NULL CHECK (question_type IN ('SINGLE_CHOICE', 'TRUE_FALSE')),
    content TEXT NOT NULL CHECK (length(trim(content)) > 0),
    explanation TEXT NOT NULL CHECK (length(trim(explanation)) > 0),
    sort_order INTEGER NOT NULL DEFAULT 0,
    published_question_id TEXT UNIQUE,
    FOREIGN KEY (draft_id) REFERENCES quiz_generation_drafts(id) ON DELETE CASCADE,
    FOREIGN KEY (published_question_id) REFERENCES questions(id) ON DELETE RESTRICT
);

CREATE INDEX idx_quiz_draft_items_draft_sort
    ON quiz_generation_draft_items(draft_id, sort_order, id);

CREATE TABLE quiz_generation_draft_options (
    id TEXT PRIMARY KEY,
    draft_item_id TEXT NOT NULL,
    content TEXT NOT NULL CHECK (length(trim(content)) > 0),
    correct INTEGER NOT NULL CHECK (correct IN (0, 1)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (draft_item_id) REFERENCES quiz_generation_draft_items(id) ON DELETE CASCADE
);

CREATE INDEX idx_quiz_draft_options_item_sort
    ON quiz_generation_draft_options(draft_item_id, sort_order, id);
