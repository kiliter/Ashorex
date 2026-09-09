-- 保存实际执行和完成时的计划日期，避免顺延改变已发生记录的归属。
ALTER TABLE todo_progress_events ADD COLUMN todo_local_date TEXT;
ALTER TABLE todos ADD COLUMN completed_local_date TEXT;
-- 旧流水缺少顺延前快照，只能以当前可用日期回填。
UPDATE todo_progress_events SET todo_local_date = (SELECT local_date FROM todos WHERE todos.id = todo_id);
UPDATE todos SET completed_local_date = local_date WHERE completed_at IS NOT NULL;
CREATE TRIGGER todo_progress_plan_snapshot AFTER INSERT ON todo_progress_events
WHEN NEW.todo_local_date IS NULL
BEGIN
  UPDATE todo_progress_events SET todo_local_date = (SELECT local_date FROM todos WHERE id = NEW.todo_id) WHERE id = NEW.id;
END;
-- 统一覆盖手动完成、播放达标及专注完成，首个完成日期归属不再随顺延改变。
CREATE TRIGGER todo_completion_plan_snapshot AFTER UPDATE OF completed_at ON todos
WHEN OLD.completed_at IS NULL AND NEW.completed_at IS NOT NULL
BEGIN
  UPDATE todos SET completed_local_date = NEW.local_date WHERE id = NEW.id;
END;
