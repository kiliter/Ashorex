-- 日终结果独立于计划终态；无计划日期也必须能够记录“自由学习”或“开摆日”。
CREATE TABLE daily_day_outcomes (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    outcome_date TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('COMPLETED','CLOSED_WITH_DEBT','FREE_STUDY','SLACKED')),
    generated_at INTEGER NOT NULL,
    UNIQUE (user_id, outcome_date),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 旧 debt_type CHECK 保持不变，debt_kind 表达 V1.3 新的 MOCK_EXAM 业务类型。
ALTER TABLE learning_debts ADD COLUMN debt_kind TEXT;
UPDATE learning_debts SET debt_kind=debt_type WHERE debt_kind IS NULL;

CREATE INDEX idx_day_outcomes_user_date ON daily_day_outcomes(user_id, outcome_date);
