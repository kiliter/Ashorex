package com.shangan.goal.infrastructure;

import com.shangan.goal.domain.ExamGoal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 考试目标的持久化边界。 */
public interface ExamGoalRepository {

  List<ExamGoal> findByUser(String userId);

  Optional<ExamGoal> findById(String id);

  void insert(ExamGoal goal, Instant now);

  void update(ExamGoal goal, Instant now);

  void delete(String id);

  /** 把该用户其余目标的主目标标记清空，保证主目标唯一。 */
  void clearPrimary(String userId, String exceptGoalId, Instant now);
}
