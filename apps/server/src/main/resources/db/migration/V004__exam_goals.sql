-- 每个用户仅有一个活动考试目标，并显式绑定参与进度计算的课程。
CREATE TABLE exam_goals (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL UNIQUE,
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

CREATE TABLE exam_goal_courses (
    exam_goal_id TEXT NOT NULL,
    course_id TEXT NOT NULL,
    PRIMARY KEY (exam_goal_id, course_id),
    FOREIGN KEY (exam_goal_id) REFERENCES exam_goals(id) ON DELETE CASCADE,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
);

CREATE INDEX idx_exam_goal_courses_course ON exam_goal_courses(course_id);
