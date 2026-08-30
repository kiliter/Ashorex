package com.shangan.planning.application;

/** 学习、答题和专注模块更新计划完成量的显式接口。 */
public interface PlanProgressPort {
  void updateProgress(String userId, String planItemId, long absoluteCompletedSeconds);

  void markQuizCompleted(String userId, String planItemId);
}
