-- 管理后台保存的单行外部服务配置；不预插入记录，以便环境变量继续作为首次启动默认值。
CREATE TABLE runtime_integration_settings (
    id TEXT PRIMARY KEY CHECK (id = 'default'),
    emby_base_url TEXT NOT NULL,
    emby_api_key TEXT NOT NULL,
    emby_user_id TEXT NOT NULL,
    llm_base_url TEXT NOT NULL,
    llm_api_key TEXT NOT NULL,
    llm_model TEXT NOT NULL,
    llm_max_context_tokens INTEGER NOT NULL CHECK (llm_max_context_tokens BETWEEN 1024 AND 1000000),
    llm_temperature REAL NOT NULL CHECK (llm_temperature BETWEEN 0 AND 2),
    llm_timeout_seconds INTEGER NOT NULL CHECK (llm_timeout_seconds BETWEEN 1 AND 600),
    asr_base_url TEXT NOT NULL,
    asr_api_key TEXT NOT NULL,
    asr_model TEXT NOT NULL,
    asr_timeout_seconds INTEGER NOT NULL CHECK (asr_timeout_seconds BETWEEN 1 AND 1800),
    mcp_url TEXT NOT NULL,
    mcp_bearer_token TEXT NOT NULL,
    mcp_allowed_tools TEXT NOT NULL,
    mcp_timeout_seconds INTEGER NOT NULL CHECK (mcp_timeout_seconds BETWEEN 1 AND 120),
    updated_at INTEGER NOT NULL
);
