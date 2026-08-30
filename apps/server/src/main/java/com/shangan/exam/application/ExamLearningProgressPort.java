package com.shangan.exam.application;

import java.util.List;

/** 可信学习模块向考试进度提供的只读完成量；考试模块不能反向修改学习数据。 */
public interface ExamLearningProgressPort {

  Completion completionFor(String userId, List<String> courseIds);

  record Completion(int completedLessons, int completedInLastSevenDays) {}
}
