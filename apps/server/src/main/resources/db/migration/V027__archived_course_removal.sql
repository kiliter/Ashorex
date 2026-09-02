-- 已归档课程可从管理区域移除，但课程与可信学习历史必须继续保留。
ALTER TABLE courses ADD COLUMN removed_at INTEGER;

CREATE INDEX idx_courses_archive_visibility
    ON courses(enabled, removed_at, sort_order);

-- 删除审计只记录安全标识，不保存 Emby 路径、密钥或课程内容。
CREATE TABLE course_removal_audits (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL,
    administrator TEXT NOT NULL,
    request_id TEXT NOT NULL,
    removed_at INTEGER NOT NULL,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
);

CREATE INDEX idx_course_removal_audits_course_time
    ON course_removal_audits(course_id, removed_at);
