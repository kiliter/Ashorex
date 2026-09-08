-- 上岸 V2 数据库基线。
--
-- 按 ADR-0031 重建：V1 的 29 个迁移与其全部表被废弃，不提供数据迁移。
-- 本文件发布后重新进入 append-only 约束，后续结构变更必须新增 V002、V003……
--
-- 约定：
--   * 所有 ID 为 UUID 字符串；
--   * 所有时间戳为 UTC Epoch 毫秒（INTEGER）；
--   * local_date 为用户时区下的 TEXT 'YYYY-MM-DD'；
--   * 指向 users 的外键用 ON DELETE CASCADE 兜底，指向 courses / learning_resources
--     的用 ON DELETE RESTRICT，强制按 ADR-0029 的顺序显式级联清理。

-- ============================================================
-- 身份与督学
-- ============================================================

CREATE TABLE users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL DEFAULT '',
    timezone TEXT NOT NULL DEFAULT 'Asia/Shanghai',
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    archived_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX idx_users_status ON users(status, username);

-- 一个账号可同时具备学习者与督学人身份；ADMIN 只用于后台 Session 登录。
CREATE TABLE user_roles (
    user_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('LEARNER', 'SUPERVISOR', 'ADMIN')),
    created_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, role),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE refresh_tokens (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id, expires_at);

-- 督学绑定：每个学员必须且只能有一个未归档的 PRIMARY 督学人。
CREATE TABLE supervisions (
    id TEXT PRIMARY KEY,
    learner_user_id TEXT NOT NULL,
    supervisor_user_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('PRIMARY', 'COLLABORATOR')),
    can_view INTEGER NOT NULL DEFAULT 1 CHECK (can_view IN (0, 1)),
    can_nag INTEGER NOT NULL DEFAULT 1 CHECK (can_nag IN (0, 1)),
    can_edit_goal INTEGER NOT NULL DEFAULT 0 CHECK (can_edit_goal IN (0, 1)),
    can_add_todo INTEGER NOT NULL DEFAULT 0 CHECK (can_add_todo IN (0, 1)),
    archived_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    CHECK (learner_user_id <> supervisor_user_id),
    FOREIGN KEY (learner_user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (supervisor_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_supervisions_pair
    ON supervisions(learner_user_id, supervisor_user_id);

CREATE UNIQUE INDEX uq_supervisions_primary
    ON supervisions(learner_user_id)
    WHERE kind = 'PRIMARY' AND archived_at IS NULL;

CREATE INDEX idx_supervisions_supervisor
    ON supervisions(supervisor_user_id, archived_at);

-- ============================================================
-- 考试目标（纯倒计时看板，不绑定任何课程）
-- ============================================================

CREATE TABLE exam_goals (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    exam_date TEXT NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    is_primary INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_exam_goals_user_date ON exam_goals(user_id, exam_date);

CREATE UNIQUE INDEX uq_exam_goals_primary
    ON exam_goals(user_id)
    WHERE is_primary = 1;

-- ============================================================
-- 课程与学习资源
-- ============================================================

CREATE TABLE courses (
    id TEXT PRIMARY KEY,
    external_source TEXT NOT NULL DEFAULT 'EMBY' CHECK (external_source IN ('EMBY')),
    external_ref TEXT NOT NULL,
    title TEXT NOT NULL,
    overview TEXT NOT NULL DEFAULT '',
    production_year INTEGER,
    sort_order INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    source_missing INTEGER NOT NULL DEFAULT 0 CHECK (source_missing IN (0, 1)),
    last_synced_at INTEGER,
    last_sync_error TEXT,
    archived_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX uq_courses_external ON courses(external_source, external_ref);
CREATE INDEX idx_courses_status_sort ON courses(status, sort_order, id);

-- 学习资源：VIDEO 为 V2 启用类型，DOCUMENT 按 ADR-0030 预留（Emby 书籍库）。
-- id 是不可变业务身份，永不重建；external_ref 是可替换的当前来源标识。
CREATE TABLE learning_resources (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL,
    resource_type TEXT NOT NULL DEFAULT 'VIDEO' CHECK (resource_type IN ('VIDEO', 'DOCUMENT')),
    title TEXT NOT NULL,
    sort_index INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0),
    page_count INTEGER CHECK (page_count IS NULL OR page_count > 0),
    external_ref TEXT NOT NULL,
    source_fingerprint TEXT,
    available INTEGER NOT NULL DEFAULT 1 CHECK (available IN (0, 1)),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    archived_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_resources_external_ref ON learning_resources(external_ref);
CREATE UNIQUE INDEX uq_resources_course_fingerprint
    ON learning_resources(course_id, source_fingerprint)
    WHERE source_fingerprint IS NOT NULL;
CREATE INDEX idx_resources_course_sort
    ON learning_resources(course_id, status, available, sort_index, id);

-- 以下三张表是 Emby 元数据的只读投影，每次同步整表重写，无人工编辑入口。
CREATE TABLE course_genres (
    course_id TEXT NOT NULL,
    genre TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (course_id, genre),
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
);

CREATE INDEX idx_course_genres_genre ON course_genres(genre, course_id);

CREATE TABLE course_tags (
    course_id TEXT NOT NULL,
    tag TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (course_id, tag),
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
);

CREATE INDEX idx_course_tags_tag ON course_tags(tag, course_id);

CREATE TABLE course_people (
    course_id TEXT NOT NULL,
    person_name TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT '',
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (course_id, person_name),
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
);

CREATE INDEX idx_course_people_person ON course_people(person_name, course_id);

-- 来源标识变更审计：记录课时在 Emby 侧换 ItemId 或重新绑定父节点时的映射依据。
CREATE TABLE resource_source_mappings (
    id TEXT PRIMARY KEY,
    resource_id TEXT NOT NULL,
    old_external_ref TEXT,
    new_external_ref TEXT NOT NULL,
    matched_by TEXT NOT NULL
        CHECK (matched_by IN ('EXTERNAL_REF', 'FINGERPRINT', 'TITLE_DURATION', 'MANUAL')),
    confirmed_by TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    FOREIGN KEY (resource_id) REFERENCES learning_resources(id) ON DELETE RESTRICT
);

CREATE INDEX idx_resource_mappings_resource
    ON resource_source_mappings(resource_id, created_at);

-- ============================================================
-- Todo（三类型共用一张表，类型专属列可空）
-- ============================================================

CREATE TABLE todos (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    local_date TEXT NOT NULL,
    todo_type TEXT NOT NULL CHECK (todo_type IN ('COURSE', 'FOCUS', 'TASK')),
    title TEXT NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    sort_order INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),

    -- 课程类
    resource_id TEXT,
    target_progress_permille INTEGER
        CHECK (target_progress_permille IS NULL
               OR (target_progress_permille BETWEEN 1 AND 1000)),
    progress_position_ms INTEGER NOT NULL DEFAULT 0 CHECK (progress_position_ms >= 0),
    progress_page INTEGER NOT NULL DEFAULT 0 CHECK (progress_page >= 0),
    watched_ms INTEGER NOT NULL DEFAULT 0 CHECK (watched_ms >= 0),

    -- 专注类
    planned_seconds INTEGER
        CHECK (planned_seconds IS NULL OR (planned_seconds BETWEEN 60 AND 43200)),
    focus_state TEXT NOT NULL DEFAULT 'IDLE'
        CHECK (focus_state IN ('IDLE', 'RUNNING', 'PAUSED', 'FINISHED', 'ABANDONED')),
    focus_started_at INTEGER,
    focused_ms INTEGER NOT NULL DEFAULT 0 CHECK (focused_ms >= 0),

    -- 全类型
    require_evidence INTEGER NOT NULL DEFAULT 0 CHECK (require_evidence IN (0, 1)),
    completed_at INTEGER,
    backfilled INTEGER NOT NULL DEFAULT 0 CHECK (backfilled IN (0, 1)),
    backfill_note TEXT,
    supervisor_user_id_snapshot TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,

    CHECK (todo_type <> 'COURSE'
           OR (resource_id IS NOT NULL AND target_progress_permille IS NOT NULL)),
    CHECK (todo_type <> 'FOCUS' OR planned_seconds IS NOT NULL),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id) REFERENCES learning_resources(id) ON DELETE RESTRICT
);

CREATE INDEX idx_todos_user_date ON todos(user_id, local_date, sort_order, id);
CREATE INDEX idx_todos_user_status ON todos(user_id, status, local_date);
CREATE INDEX idx_todos_resource ON todos(resource_id);
CREATE UNIQUE INDEX uq_todos_running_focus
    ON todos(user_id)
    WHERE focus_state = 'RUNNING';

-- 备注一键标签，用于数据 Tab 聚合与反查。
CREATE TABLE todo_note_tags (
    todo_id TEXT NOT NULL,
    tag TEXT NOT NULL
        CHECK (tag IN ('MASTERED', 'NEED_REVIEW', 'HAS_QUESTION', 'NOTED', 'BEHIND')),
    PRIMARY KEY (todo_id, tag),
    FOREIGN KEY (todo_id) REFERENCES todos(id) ON DELETE CASCADE
);

CREATE INDEX idx_todo_note_tags_tag ON todo_note_tags(tag, todo_id);

CREATE TABLE todo_attachments (
    id TEXT PRIMARY KEY,
    todo_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    storage_path TEXT NOT NULL UNIQUE,
    original_filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256 TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (todo_id) REFERENCES todos(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_todo_attachments_todo ON todo_attachments(todo_id, sort_order, id);
CREATE INDEX idx_todo_attachments_user ON todo_attachments(user_id);

-- 进度流水：(todo_id, client_seq) 唯一保证客户端重放幂等。
CREATE TABLE todo_progress_events (
    id TEXT PRIMARY KEY,
    todo_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    client_seq INTEGER NOT NULL CHECK (client_seq >= 0),
    event_type TEXT NOT NULL
        CHECK (event_type IN ('PROGRESS', 'PAUSE', 'RESUME', 'COMPLETE',
                              'FOCUS_TICK', 'FOCUS_PAUSE', 'FOCUS_FINISH', 'FOCUS_ABANDON')),
    position_ms INTEGER,
    position_page INTEGER,
    delta_watched_ms INTEGER NOT NULL DEFAULT 0 CHECK (delta_watched_ms >= 0),
    delta_focused_ms INTEGER NOT NULL DEFAULT 0 CHECK (delta_focused_ms >= 0),
    app_state TEXT NOT NULL DEFAULT 'FOREGROUND'
        CHECK (app_state IN ('FOREGROUND', 'BACKGROUND')),
    occurred_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (todo_id) REFERENCES todos(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_progress_events_seq ON todo_progress_events(todo_id, client_seq);
CREATE INDEX idx_progress_events_user_time ON todo_progress_events(user_id, occurred_at);

-- 跨 Todo 的课时累计状态；同一课时被重复加入 Todo（复看）时不清零。
CREATE TABLE lesson_watch_states (
    user_id TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    max_position_ms INTEGER NOT NULL DEFAULT 0 CHECK (max_position_ms >= 0),
    max_position_page INTEGER NOT NULL DEFAULT 0 CHECK (max_position_page >= 0),
    total_watched_ms INTEGER NOT NULL DEFAULT 0 CHECK (total_watched_ms >= 0),
    completed_count INTEGER NOT NULL DEFAULT 0 CHECK (completed_count >= 0),
    last_watched_at INTEGER,
    PRIMARY KEY (user_id, resource_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id) REFERENCES learning_resources(id) ON DELETE RESTRICT
);

CREATE INDEX idx_watch_states_resource ON lesson_watch_states(resource_id);

-- 删除台账：Todo 行被物理删除，但删除记录与当时进度快照永久保留。
CREATE TABLE todo_deletions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    todo_id TEXT NOT NULL,
    todo_type TEXT NOT NULL CHECK (todo_type IN ('COURSE', 'FOCUS', 'TASK')),
    local_date TEXT NOT NULL,
    title_snapshot TEXT NOT NULL,
    resource_id TEXT,
    progress_snapshot_json TEXT NOT NULL DEFAULT '{}',
    reason_tag TEXT NOT NULL
        CHECK (reason_tag IN ('TOO_MANY_PLANNED', 'TEMP_BUSY', 'ADDED_BY_MISTAKE',
                              'SWITCHED_TO_OTHER', 'GAVE_UP')),
    reason_text TEXT NOT NULL,
    supervisor_user_id_snapshot TEXT,
    deleted_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_todo_deletions_user_time ON todo_deletions(user_id, deleted_at);
CREATE INDEX idx_todo_deletions_reason ON todo_deletions(reason_tag, deleted_at);
CREATE INDEX idx_todo_deletions_resource ON todo_deletions(resource_id);

-- ============================================================
-- 在线状态（不保存心跳明细历史）
-- ============================================================

CREATE TABLE user_presence (
    user_id TEXT PRIMARY KEY,
    last_heartbeat_at INTEGER,
    last_effective_action_at INTEGER,
    app_state TEXT NOT NULL DEFAULT 'BACKGROUND'
        CHECK (app_state IN ('FOREGROUND', 'BACKGROUND')),
    client_version TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================
-- 催办
-- ============================================================

-- scope=GLOBAL 为全局默认（固定一行）；scope=USER 为按用户覆盖。
-- 覆盖只影响阈值与渠道类字段，调度类字段（scan/grace/heartbeat）只取全局值。
CREATE TABLE nag_policies (
    id TEXT PRIMARY KEY,
    scope TEXT NOT NULL CHECK (scope IN ('GLOBAL', 'USER')),
    user_id TEXT,
    scan_interval_minutes INTEGER NOT NULL DEFAULT 5
        CHECK (scan_interval_minutes BETWEEN 1 AND 60),
    presence_grace_seconds INTEGER NOT NULL DEFAULT 150
        CHECK (presence_grace_seconds BETWEEN 30 AND 900),
    heartbeat_interval_seconds INTEGER NOT NULL DEFAULT 60
        CHECK (heartbeat_interval_seconds BETWEEN 30 AND 300),
    first_threshold_minutes INTEGER NOT NULL DEFAULT 90
        CHECK (first_threshold_minutes BETWEEN 10 AND 720),
    repeat_interval_minutes INTEGER NOT NULL DEFAULT 60
        CHECK (repeat_interval_minutes BETWEEN 0 AND 720),
    daily_max INTEGER NOT NULL DEFAULT 3 CHECK (daily_max BETWEEN 1 AND 10),
    fullscreen_timeout_minutes INTEGER NOT NULL DEFAULT 10
        CHECK (fullscreen_timeout_minutes BETWEEN 1 AND 60),
    quiet_start TEXT NOT NULL DEFAULT '23:30',
    quiet_end TEXT NOT NULL DEFAULT '07:00',
    min_pending INTEGER NOT NULL DEFAULT 1 CHECK (min_pending >= 1),
    min_reason_length INTEGER NOT NULL DEFAULT 5 CHECK (min_reason_length BETWEEN 1 AND 200),
    channel_fullscreen_enabled INTEGER NOT NULL DEFAULT 1
        CHECK (channel_fullscreen_enabled IN (0, 1)),
    channel_serverchan_enabled INTEGER NOT NULL DEFAULT 1
        CHECK (channel_serverchan_enabled IN (0, 1)),
    message_template TEXT NOT NULL
        DEFAULT '{{user}} 今天还有 {{pending}} 项未完成，已经 {{idleMinutes}} 分钟没有任何操作。',
    notify_supervisor_on_bulk_delete INTEGER NOT NULL DEFAULT 1
        CHECK (notify_supervisor_on_bulk_delete IN (0, 1)),
    notify_supervisor_on_gave_up INTEGER NOT NULL DEFAULT 1
        CHECK (notify_supervisor_on_gave_up IN (0, 1)),
    notify_supervisor_on_half_done_delete INTEGER NOT NULL DEFAULT 1
        CHECK (notify_supervisor_on_half_done_delete IN (0, 1)),
    updated_at INTEGER NOT NULL,
    CHECK ((scope = 'GLOBAL' AND user_id IS NULL) OR (scope = 'USER' AND user_id IS NOT NULL)),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_nag_policies_global ON nag_policies(scope) WHERE scope = 'GLOBAL';
CREATE UNIQUE INDEX uq_nag_policies_user ON nag_policies(user_id) WHERE scope = 'USER';

CREATE TABLE nags (
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
        CHECK (status IN ('PENDING', 'DELIVERED', 'RESPONDED', 'EXPIRED')),
    delivered_at INTEGER,
    responded_at INTEGER,
    reason_tag TEXT,
    reason_text TEXT,
    supervisor_user_id_snapshot TEXT,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_nags_auto_level
    ON nags(user_id, local_date, threshold_level)
    WHERE trigger_source = 'AUTO';
CREATE INDEX idx_nags_user_status ON nags(user_id, status, created_at);
CREATE INDEX idx_nags_date ON nags(local_date, created_at);

CREATE TABLE nag_deliveries (
    id TEXT PRIMARY KEY,
    nag_id TEXT NOT NULL,
    channel TEXT NOT NULL CHECK (channel IN ('FULLSCREEN', 'SERVERCHAN')),
    status TEXT NOT NULL CHECK (status IN ('SENT', 'SHOWN', 'FAILED')),
    detail TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    FOREIGN KEY (nag_id) REFERENCES nags(id) ON DELETE CASCADE
);

CREATE INDEX idx_nag_deliveries_nag ON nag_deliveries(nag_id, created_at);

-- ============================================================
-- 运维
-- ============================================================

-- 只记录删了什么实体、各表删了多少行、谁执行的；不保存被删内容明细。
CREATE TABLE deletion_audits (
    id TEXT PRIMARY KEY,
    entity_type TEXT NOT NULL
        CHECK (entity_type IN ('COURSE', 'LEARNING_RESOURCE', 'USER', 'SUPERVISION')),
    entity_id TEXT NOT NULL,
    entity_label TEXT NOT NULL,
    actor TEXT NOT NULL,
    row_counts_json TEXT NOT NULL DEFAULT '{}',
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_deletion_audits_time ON deletion_audits(created_at);

-- 运行配置固定单行；Emby 媒体库绑定以 JSON 数组存储。
CREATE TABLE runtime_settings (
    id TEXT PRIMARY KEY,
    emby_base_url TEXT NOT NULL DEFAULT '',
    emby_api_key TEXT NOT NULL DEFAULT '',
    emby_user_id TEXT NOT NULL DEFAULT '',
    emby_timeout_seconds INTEGER NOT NULL DEFAULT 10
        CHECK (emby_timeout_seconds BETWEEN 1 AND 120),
    emby_libraries_json TEXT NOT NULL DEFAULT '[]',
    serverchan_send_key TEXT NOT NULL DEFAULT '',
    serverchan_timeout_seconds INTEGER NOT NULL DEFAULT 8
        CHECK (serverchan_timeout_seconds BETWEEN 1 AND 60),
    serverchan_nag_enabled INTEGER NOT NULL DEFAULT 1
        CHECK (serverchan_nag_enabled IN (0, 1)),
    serverchan_daily_digest_enabled INTEGER NOT NULL DEFAULT 0
        CHECK (serverchan_daily_digest_enabled IN (0, 1)),
    feature_document_resources INTEGER NOT NULL DEFAULT 0
        CHECK (feature_document_resources IN (0, 1)),
    feature_max_document_size_mb INTEGER NOT NULL DEFAULT 200
        CHECK (feature_max_document_size_mb BETWEEN 1 AND 2048),
    archive_retention_days INTEGER NOT NULL DEFAULT 30
        CHECK (archive_retention_days BETWEEN 1 AND 365),
    updated_at INTEGER NOT NULL
);

-- ============================================================
-- Spring Session（管理后台持久化会话）
-- ============================================================

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BLOB NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

-- ============================================================
-- 初始数据
-- ============================================================

INSERT INTO nag_policies (id, scope, user_id, updated_at)
VALUES ('nag-policy-global', 'GLOBAL', NULL, 0);

INSERT INTO runtime_settings (id, updated_at) VALUES ('runtime', 0);
