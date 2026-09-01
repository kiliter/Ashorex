-- OpenRouter 返回的最大输出 Tokens 由模型目录决定，不再设置固定上限。
-- SQLite 无法直接删除 CHECK，因此按原字段顺序重建单行运行时配置表。
ALTER TABLE runtime_integration_settings RENAME TO runtime_integration_settings_v015;

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
    llm_max_completion_tokens INTEGER NOT NULL,
    llm_timeout_seconds INTEGER NOT NULL CHECK (llm_timeout_seconds BETWEEN 1 AND 1800),
    openrouter_api_key TEXT NOT NULL,
    content_auto_fill_enabled INTEGER NOT NULL CHECK (content_auto_fill_enabled IN (0, 1)),
    content_auto_fill_interval_minutes INTEGER NOT NULL CHECK (content_auto_fill_interval_minutes BETWEEN 1 AND 1440),
    updated_at INTEGER NOT NULL,
    llm_reasoning_effort TEXT NOT NULL DEFAULT ''
);

INSERT INTO runtime_integration_settings (
    id, emby_base_url, emby_api_key, emby_user_id,
    asr_base_url, asr_api_key, asr_model, asr_language,
    asr_chunk_duration_seconds, asr_timeout_seconds,
    llm_base_url, llm_api_key, llm_model, llm_context_length,
    llm_max_completion_tokens, llm_timeout_seconds,
    openrouter_api_key, content_auto_fill_enabled,
    content_auto_fill_interval_minutes, updated_at, llm_reasoning_effort
)
SELECT
    id, emby_base_url, emby_api_key, emby_user_id,
    asr_base_url, asr_api_key, asr_model, asr_language,
    asr_chunk_duration_seconds, asr_timeout_seconds,
    llm_base_url, llm_api_key, llm_model, llm_context_length,
    llm_max_completion_tokens, llm_timeout_seconds,
    openrouter_api_key, content_auto_fill_enabled,
    content_auto_fill_interval_minutes, updated_at, llm_reasoning_effort
FROM runtime_integration_settings_v015;

DROP TABLE runtime_integration_settings_v015;
