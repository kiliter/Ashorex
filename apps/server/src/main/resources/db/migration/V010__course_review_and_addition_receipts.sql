-- 复习是课程的增量标记；已发布待办默认保持普通课程。
ALTER TABLE todos ADD COLUMN is_review INTEGER NOT NULL DEFAULT 0 CHECK (is_review IN (0, 1));

-- 回执独立于 Todo 保留：删除待办后重试也不能复活旧请求；删除用户时级联清理。
CREATE TABLE course_addition_receipts (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    result_json TEXT,
    PRIMARY KEY (user_id, request_id)
);
