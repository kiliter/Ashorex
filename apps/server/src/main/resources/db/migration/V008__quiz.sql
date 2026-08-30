-- 课后题目、选项、答题尝试和逐题作答明细。
CREATE TABLE questions (
    id TEXT PRIMARY KEY,
    media_item_id TEXT NOT NULL,
    question_type TEXT NOT NULL CHECK (question_type IN ('SINGLE_CHOICE','TRUE_FALSE')),
    content TEXT NOT NULL,
    explanation TEXT NOT NULL DEFAULT '',
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE TABLE question_options (
    id TEXT PRIMARY KEY,
    question_id TEXT NOT NULL,
    content TEXT NOT NULL,
    correct INTEGER NOT NULL CHECK (correct IN (0,1)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE
);

CREATE TABLE quiz_attempts (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    media_item_id TEXT NOT NULL,
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    correct_count INTEGER NOT NULL CHECK (correct_count >= 0),
    total_count INTEGER NOT NULL CHECK (total_count > 0),
    duration_ms INTEGER NOT NULL CHECK (duration_ms >= 0),
    submitted_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE TABLE quiz_answers (
    id TEXT PRIMARY KEY,
    attempt_id TEXT NOT NULL,
    question_id TEXT NOT NULL,
    selected_option_id TEXT NOT NULL,
    correct INTEGER NOT NULL CHECK (correct IN (0,1)),
    duration_ms INTEGER NOT NULL CHECK (duration_ms >= 0),
    created_at INTEGER NOT NULL,
    UNIQUE (attempt_id, question_id),
    FOREIGN KEY (attempt_id) REFERENCES quiz_attempts(id) ON DELETE CASCADE,
    FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE RESTRICT,
    FOREIGN KEY (selected_option_id) REFERENCES question_options(id) ON DELETE RESTRICT
);

CREATE INDEX idx_questions_media_enabled_sort
    ON questions(media_item_id, enabled, sort_order);
CREATE INDEX idx_quiz_attempts_user_media_submitted
    ON quiz_attempts(user_id, media_item_id, submitted_at);
