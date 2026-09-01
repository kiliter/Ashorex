-- 现有用户补齐三个开箱即用的考试预置；同名预置存在时保留用户自己的名称与时长。
INSERT INTO mock_exam_presets (
    id, user_id, name, duration_seconds, sort_order, created_at, updated_at
)
SELECT u.id || ':default-exam:xingce', u.id, '行测', 7200, 0,
       CAST(strftime('%s', 'now') AS INTEGER) * 1000,
       CAST(strftime('%s', 'now') AS INTEGER) * 1000
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM mock_exam_presets p WHERE p.user_id = u.id AND p.name = '行测'
);

INSERT INTO mock_exam_presets (
    id, user_id, name, duration_seconds, sort_order, created_at, updated_at
)
SELECT u.id || ':default-exam:shenlun', u.id, '申论', 10800, 1,
       CAST(strftime('%s', 'now') AS INTEGER) * 1000,
       CAST(strftime('%s', 'now') AS INTEGER) * 1000
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM mock_exam_presets p WHERE p.user_id = u.id AND p.name = '申论'
);

-- 大作文 180 分钟是产品约定的专项完整模拟时长，不表示官方存在独立的大作文科目。
INSERT INTO mock_exam_presets (
    id, user_id, name, duration_seconds, sort_order, created_at, updated_at
)
SELECT u.id || ':default-exam:essay', u.id, '大作文', 10800, 2,
       CAST(strftime('%s', 'now') AS INTEGER) * 1000,
       CAST(strftime('%s', 'now') AS INTEGER) * 1000
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM mock_exam_presets p WHERE p.user_id = u.id AND p.name = '大作文'
);
