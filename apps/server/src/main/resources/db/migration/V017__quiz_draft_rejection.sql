-- 题目审核增加“驳回”状态；整份删除继续依赖明细和选项的级联外键。
DROP INDEX idx_quiz_draft_options_item_sort;
DROP INDEX idx_quiz_draft_items_draft_sort;
DROP INDEX idx_quiz_drafts_course_status;

ALTER TABLE quiz_generation_draft_options RENAME TO quiz_generation_draft_options_v014;
ALTER TABLE quiz_generation_draft_items RENAME TO quiz_generation_draft_items_v014;
ALTER TABLE quiz_generation_drafts RENAME TO quiz_generation_drafts_v014;

CREATE TABLE quiz_generation_drafts (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL UNIQUE,
    course_id TEXT NOT NULL,
    media_item_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('READY_FOR_REVIEW', 'REJECTED', 'PUBLISHED')),
    requested_question_count INTEGER NOT NULL CHECK (requested_question_count BETWEEN 1 AND 20),
    created_at INTEGER NOT NULL,
    published_at INTEGER,
    FOREIGN KEY (job_id) REFERENCES content_generation_jobs(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE INDEX idx_quiz_drafts_course_status
    ON quiz_generation_drafts(course_id, status, created_at DESC);

CREATE TABLE quiz_generation_draft_items (
    id TEXT PRIMARY KEY,
    draft_id TEXT NOT NULL,
    question_type TEXT NOT NULL CHECK (question_type IN ('SINGLE_CHOICE', 'TRUE_FALSE')),
    content TEXT NOT NULL CHECK (length(trim(content)) > 0),
    explanation TEXT NOT NULL CHECK (length(trim(explanation)) > 0),
    sort_order INTEGER NOT NULL DEFAULT 0,
    published_question_id TEXT UNIQUE,
    FOREIGN KEY (draft_id) REFERENCES quiz_generation_drafts(id) ON DELETE CASCADE,
    FOREIGN KEY (published_question_id) REFERENCES questions(id) ON DELETE RESTRICT
);

CREATE INDEX idx_quiz_draft_items_draft_sort
    ON quiz_generation_draft_items(draft_id, sort_order, id);

CREATE TABLE quiz_generation_draft_options (
    id TEXT PRIMARY KEY,
    draft_item_id TEXT NOT NULL,
    content TEXT NOT NULL CHECK (length(trim(content)) > 0),
    correct INTEGER NOT NULL CHECK (correct IN (0, 1)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (draft_item_id) REFERENCES quiz_generation_draft_items(id) ON DELETE CASCADE
);

CREATE INDEX idx_quiz_draft_options_item_sort
    ON quiz_generation_draft_options(draft_item_id, sort_order, id);

INSERT INTO quiz_generation_drafts
SELECT * FROM quiz_generation_drafts_v014;

INSERT INTO quiz_generation_draft_items
SELECT * FROM quiz_generation_draft_items_v014;

INSERT INTO quiz_generation_draft_options
SELECT * FROM quiz_generation_draft_options_v014;

DROP TABLE quiz_generation_draft_options_v014;
DROP TABLE quiz_generation_draft_items_v014;
DROP TABLE quiz_generation_drafts_v014;
