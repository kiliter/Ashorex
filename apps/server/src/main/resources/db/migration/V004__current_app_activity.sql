-- 仅扩展每用户单行快照；不记录页面历史，不修改有效操作时间。
ALTER TABLE user_presence ADD COLUMN current_page TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE user_presence ADD COLUMN activity_state TEXT NOT NULL DEFAULT 'UNKNOWN';
-- 不设级联外键：待办删除后保留最后页面，名称在查询时重新核对归属。
ALTER TABLE user_presence ADD COLUMN activity_todo_id TEXT;
