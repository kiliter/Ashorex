-- 身份查询与 Token 清理使用的索引；迁移保持追加式。
CREATE INDEX idx_users_role_enabled ON users(role, enabled);
CREATE INDEX idx_refresh_tokens_active ON refresh_tokens(user_id, revoked_at, expires_at);
