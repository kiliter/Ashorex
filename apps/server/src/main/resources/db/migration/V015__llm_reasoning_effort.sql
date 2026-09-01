-- LLM 思考等级为空时不向 OpenAI-compatible 上游发送 reasoning_effort。
ALTER TABLE runtime_integration_settings
    ADD COLUMN llm_reasoning_effort TEXT NOT NULL DEFAULT '';
