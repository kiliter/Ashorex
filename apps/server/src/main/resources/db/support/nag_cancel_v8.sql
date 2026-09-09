-- 新增管理员取消终态，操作数组随催办生命周期保留。
CREATE TABLE nags_v8 (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    local_date TEXT NOT NULL,
    threshold_level INTEGER NOT NULL CHECK (threshold_level >= 1),
    trigger_source TEXT NOT NULL CHECK (trigger_source IN ('AUTO', 'MANUAL', 'SUPERVISOR')),
    triggered_by_user_id TEXT,
    idle_minutes INTEGER NOT NULL DEFAULT 0 CHECK (idle_minutes >= 0),
    pending_count INTEGER NOT NULL DEFAULT 0 CHECK (pending_count >= 0),
    message TEXT NOT NULL DEFAULT '',
    require_reason INTEGER NOT NULL DEFAULT 1 CHECK (require_reason IN (0, 1)),
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'DELIVERED', 'RESPONDED', 'EXPIRED', 'CANCELLED')),
    delivered_at INTEGER,
    responded_at INTEGER,
    reason_tag TEXT,
    reason_text TEXT,
    supervisor_user_id_snapshot TEXT,
    created_at INTEGER NOT NULL,
    title TEXT,
    admin_actions TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(admin_actions) AND json_type(admin_actions) = 'array'),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 显式列映射保留已发布 V005 标题和所有原始字段。
INSERT INTO nags_v8 (id,user_id,local_date,threshold_level,trigger_source,triggered_by_user_id,idle_minutes,pending_count,message,require_reason,status,delivered_at,responded_at,reason_tag,reason_text,supervisor_user_id_snapshot,created_at,title)
SELECT id,user_id,local_date,threshold_level,trigger_source,triggered_by_user_id,idle_minutes,pending_count,message,require_reason,status,delivered_at,responded_at,reason_tag,reason_text,supervisor_user_id_snapshot,created_at,title FROM nags;
DROP TABLE nags;
ALTER TABLE nags_v8 RENAME TO nags;
CREATE UNIQUE INDEX uq_nags_auto_level
    ON nags(user_id, local_date, threshold_level)
    WHERE trigger_source = 'AUTO';
CREATE INDEX idx_nags_user_status ON nags(user_id, status, created_at);
CREATE INDEX idx_nags_date ON nags(local_date, created_at);
