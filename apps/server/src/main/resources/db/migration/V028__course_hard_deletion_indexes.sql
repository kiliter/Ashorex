-- 为已归档课程完整关联图统计和叶子到根删除补充索引；不改变既有数据与外键语义。
CREATE INDEX IF NOT EXISTS idx_plan_items_media_item ON daily_plan_items(media_item_id);
CREATE INDEX IF NOT EXISTS idx_plan_items_debt ON daily_plan_items(debt_id);
CREATE INDEX IF NOT EXISTS idx_learning_debts_media_item ON learning_debts(media_item_id);
CREATE INDEX IF NOT EXISTS idx_learning_debts_source_item ON learning_debts(source_plan_item_id);
CREATE INDEX IF NOT EXISTS idx_watch_sessions_media_item ON watch_sessions(media_item_id);
CREATE INDEX IF NOT EXISTS idx_focus_sessions_media_item ON focus_sessions(media_item_id);
CREATE INDEX IF NOT EXISTS idx_review_events_media_item ON lesson_review_events(media_item_id);
CREATE INDEX IF NOT EXISTS idx_content_jobs_media_item ON content_generation_jobs(media_item_id);
CREATE INDEX IF NOT EXISTS idx_quiz_drafts_media_item ON quiz_generation_drafts(media_item_id);
