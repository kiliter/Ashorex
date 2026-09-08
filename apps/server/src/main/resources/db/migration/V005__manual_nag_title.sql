-- 手动催办可选标题；历史和自动催办保持原渠道默认标题。
ALTER TABLE nags ADD COLUMN title TEXT;
