package com.shangan.exam.application;

import java.util.List;
import org.springframework.stereotype.Component;

/** 可信观看功能落地前的零值实现；Task 9 将其替换为真实完成记录查询。 */
@Component
public class EmptyExamLearningProgress implements ExamLearningProgressPort {

  @Override
  public Completion completionFor(String userId, List<String> courseIds) {
    return new Completion(0, 0);
  }
}
