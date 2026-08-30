package com.shangan.planning.application;

/** 学习、答题和专注模块更新计划完成量的显式接口。 */
public interface PlanProgressPort {
  void updateProgress(String userId, String planItemId, long absoluteCompletedSeconds);

  /** 使用视频绝对可信位置更新任务，并独立传入完成阈值结果。 */
  void updateVideoWatchProgress(
      String userId, String planItemId, long absoluteCompletedSeconds, boolean watchCompleted);

  void markQuizCompleted(String userId, String planItemId);
}
