-- App 合并标记与催办投递状态独立，Bark 的失败重投和逐条通知保留。
ALTER TABLE nags ADD COLUMN app_superseded INTEGER NOT NULL DEFAULT 0 CHECK (app_superseded IN (0, 1));

-- 升级时收敛存量未回应记录，最新已回应记录也阻止旧提醒重新出现。
UPDATE nags AS older SET app_superseded = 1
 WHERE older.app_superseded = 0 AND older.status IN ('PENDING', 'DELIVERED')
   AND EXISTS (
     SELECT 1 FROM nags AS newer
      WHERE newer.user_id = older.user_id AND newer.local_date = older.local_date
        AND newer.status IN ('DELIVERED', 'RESPONDED')
        AND (newer.created_at > older.created_at
             OR (newer.created_at = older.created_at AND newer.id > older.id))
   );
