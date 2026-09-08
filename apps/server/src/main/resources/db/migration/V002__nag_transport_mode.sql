-- 客户端通知方式只读取全局策略；保留旧心跳模式作为可切换回退。
ALTER TABLE nag_policies ADD COLUMN transport_mode TEXT NOT NULL DEFAULT 'SSE' CHECK (transport_mode IN ('SSE', 'HEARTBEAT'));
