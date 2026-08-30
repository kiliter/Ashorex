package com.shangan.exam.application;

import java.util.List;

/** 早期脚手架保留的零值实现；生产环境已由可信视频进度仓储替代。 */
public class EmptyExamLearningProgress implements ExamLearningProgressPort {

  @Override
  public Completion completionFor(String userId, List<String> courseIds) {
    return new Completion(0, 0);
  }
}
