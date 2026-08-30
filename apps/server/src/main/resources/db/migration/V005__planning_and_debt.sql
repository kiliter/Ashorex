-- 每日计划、开摆记录、欠债台账和还债明细。
CREATE TABLE daily_plans (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    plan_date TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'LOCKED', 'COMPLETED', 'ABANDONED', 'CLOSED_WITH_DEBT')),
    locked_at INTEGER,
    closed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (user_id, plan_date),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE daily_plan_items (
    id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL,
    item_type TEXT NOT NULL CHECK (item_type IN ('VIDEO', 'FOCUS', 'QUIZ', 'DEBT_REPAYMENT')),
    title TEXT NOT NULL,
    media_item_id TEXT,
    debt_id TEXT,
    planned_seconds INTEGER NOT NULL CHECK (planned_seconds > 0),
    completed_seconds INTEGER NOT NULL DEFAULT 0 CHECK (completed_seconds >= 0),
    watch_completed INTEGER NOT NULL DEFAULT 0 CHECK (watch_completed IN (0, 1)),
    quiz_required INTEGER NOT NULL DEFAULT 0 CHECK (quiz_required IN (0, 1)),
    quiz_completed INTEGER NOT NULL DEFAULT 0 CHECK (quiz_completed IN (0, 1)),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED')),
    sort_order INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES daily_plans(id) ON DELETE CASCADE,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE TABLE plan_abandonments (
    id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    reason_text TEXT NOT NULL,
    remaining_seconds INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES daily_plans(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE learning_debts (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    source_plan_item_id TEXT NOT NULL,
    debt_type TEXT NOT NULL CHECK (debt_type IN ('VIDEO_WATCH', 'QUIZ', 'FOCUS')),
    media_item_id TEXT,
    title TEXT NOT NULL,
    original_seconds INTEGER NOT NULL CHECK (original_seconds >= 0),
    remaining_seconds INTEGER NOT NULL CHECK (remaining_seconds >= 0),
    baseline_completed_seconds INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'PARTIALLY_REPAID', 'PAID', 'WAIVED')),
    reason TEXT NOT NULL,
    opened_on TEXT NOT NULL,
    paid_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (source_plan_item_id, debt_type),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (source_plan_item_id) REFERENCES daily_plan_items(id) ON DELETE RESTRICT,
    FOREIGN KEY (media_item_id) REFERENCES media_items(id) ON DELETE RESTRICT
);

CREATE TABLE debt_repayments (
    id TEXT PRIMARY KEY,
    debt_id TEXT NOT NULL,
    plan_item_id TEXT,
    repaid_seconds INTEGER NOT NULL CHECK (repaid_seconds > 0),
    repayment_source TEXT NOT NULL CHECK (repayment_source IN ('PLAN_ITEM', 'DIRECT_VIDEO', 'DIRECT_QUIZ', 'ADMIN')),
    created_at INTEGER NOT NULL,
    FOREIGN KEY (debt_id) REFERENCES learning_debts(id) ON DELETE RESTRICT,
    FOREIGN KEY (plan_item_id) REFERENCES daily_plan_items(id) ON DELETE RESTRICT
);

CREATE INDEX idx_plans_user_date ON daily_plans(user_id, plan_date);
CREATE INDEX idx_plan_items_plan_sort ON daily_plan_items(plan_id, sort_order);
CREATE INDEX idx_debts_user_status ON learning_debts(user_id, status, opened_on);
CREATE INDEX idx_repayments_debt ON debt_repayments(debt_id, created_at);
