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
}
