package com.shangan.exam.infrastructure;

import com.shangan.exam.domain.ExamGoal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 考试目标持久化边界，所有查询都必须显式携带用户 ID。 */
public interface ExamGoalRepository {

  Optional<ExamGoal> findByUserId(String userId);

  List<ExamGoal> listByUserId(String userId);

  Optional<ExamGoal> findById(String userId, String goalId);

  void save(ExamGoal goal, Instant now);

  /**
   * 删除属于该用户的考试目标及其课程绑定。
   *
   * @return 目标存在且归属该用户时返回 true；否则返回 false 由应用服务转换为业务错误
   */
  boolean delete(String userId, String goalId);
}
