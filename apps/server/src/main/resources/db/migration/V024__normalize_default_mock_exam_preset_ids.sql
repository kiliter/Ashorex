-- 已执行的 V022 使用了可读复合 ID；追加迁移统一改为 UUID，并同步作战单中的预置引用。
CREATE TEMP TABLE v024_mock_exam_preset_ids (
    old_id TEXT PRIMARY KEY,
    new_id TEXT NOT NULL UNIQUE
);

INSERT INTO v024_mock_exam_preset_ids (old_id, new_id)
SELECT id,
       lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
       substr(lower(hex(randomblob(2))), 2) || '-' ||
       substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' ||
       lower(hex(randomblob(6)))
FROM mock_exam_presets
WHERE instr(id, ':default-exam:') > 0;

UPDATE daily_plan_items
SET mock_exam_preset_id = (
    SELECT ids.new_id
    FROM v024_mock_exam_preset_ids ids
    WHERE ids.old_id = daily_plan_items.mock_exam_preset_id
)
WHERE mock_exam_preset_id IN (SELECT old_id FROM v024_mock_exam_preset_ids);

UPDATE mock_exam_presets
SET id = (
    SELECT ids.new_id
    FROM v024_mock_exam_preset_ids ids
    WHERE ids.old_id = mock_exam_presets.id
)
WHERE id IN (SELECT old_id FROM v024_mock_exam_preset_ids);

DROP TABLE v024_mock_exam_preset_ids;
