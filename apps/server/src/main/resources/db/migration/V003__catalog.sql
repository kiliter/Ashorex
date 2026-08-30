-- Emby 课程绑定与本地课时快照；远端下线只更新 available，不删除历史数据。
CREATE TABLE courses (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    emby_parent_item_id TEXT NOT NULL UNIQUE,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    last_synced_at INTEGER,
    last_sync_error TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE media_items (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL,
    emby_item_id TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    duration_ms INTEGER NOT NULL CHECK (duration_ms >= 0),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    available INTEGER NOT NULL DEFAULT 1 CHECK (available IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
);

CREATE INDEX idx_courses_enabled_sort ON courses(enabled, sort_order);
CREATE INDEX idx_media_items_course_sort ON media_items(course_id, enabled, available, sort_order);
