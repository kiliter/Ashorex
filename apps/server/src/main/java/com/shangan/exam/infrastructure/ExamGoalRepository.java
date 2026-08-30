package com.shangan.exam.infrastructure;

import com.shangan.exam.domain.ExamGoal;
import java.time.Instant;
import java.util.Optional;

/** 考试目标持久化边界，所有查询都必须显式携带用户 ID。 */
public interface ExamGoalRepository {

  Optional<ExamGoal> findByUserId(String userId);

  void save(ExamGoal goal, Instant now);
}
