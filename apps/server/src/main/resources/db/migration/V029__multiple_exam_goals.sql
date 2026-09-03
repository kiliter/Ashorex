-- 同一用户可保存多个考试目标。先备份课程绑定，再重建去掉 user_id UNIQUE 的考试表。
CREATE TABLE exam_goal_courses_backup AS
SELECT exam_goal_id, course_id FROM exam_goal_courses;

DROP TABLE exam_goal_courses;

CREATE TABLE exam_goals_v029 (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    exam_date TEXT NOT NULL,
    target_completion_date TEXT NOT NULL,
    review_buffer_days INTEGER NOT NULL DEFAULT 14
        CHECK (review_buffer_days >= 0 AND review_buffer_days <= 365),
    timezone TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

INSERT INTO exam_goals_v029 (
    id, user_id, name, exam_date, target_completion_date,
    review_buffer_days, timezone, created_at, updated_at
)
SELECT
    id, user_id, name, exam_date, target_completion_date,
    review_buffer_days, timezone, created_at, updated_at
FROM exam_goals;

DROP TABLE exam_goals;
ALTER TABLE exam_goals_v029 RENAME TO exam_goals;

CREATE INDEX idx_exam_goals_user_exam_date ON exam_goals(user_id, exam_date);

CREATE TABLE exam_goal_courses (
    exam_goal_id TEXT NOT NULL,
    course_id TEXT NOT NULL,
    PRIMARY KEY (exam_goal_id, course_id),
    FOREIGN KEY (exam_goal_id) REFERENCES exam_goals(id) ON DELETE CASCADE,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
);

INSERT INTO exam_goal_courses (exam_goal_id, course_id)
SELECT exam_goal_id, course_id FROM exam_goal_courses_backup;

DROP TABLE exam_goal_courses_backup;

CREATE INDEX idx_exam_goal_courses_course ON exam_goal_courses(course_id);
