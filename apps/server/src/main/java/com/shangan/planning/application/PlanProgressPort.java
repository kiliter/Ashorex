package com.shangan.planning.application;

/** 学习、答题和专注模块更新计划完成量的显式接口。 */
public interface PlanProgressPort {
  /** 复习快捷入口只用于识别播放模式，默认实现兼容不关注该概念的测试替身。 */
  default boolean isReviewShortcut(String userId, String planItemId, String mediaItemId) {
    return false;
  }

  void updateProgress(String userId, String planItemId, long absoluteCompletedSeconds);

  /** 使用视频绝对可信位置更新任务，并独立传入完成阈值结果。 */
  void updateVideoWatchProgress(
      String userId, String planItemId, long absoluteCompletedSeconds, boolean watchCompleted);

  void markQuizCompleted(String userId, String planItemId);

  /** 答题提交前校验任务所有权、任务组成类型以及关联视频。 */
  void validateQuizLink(String userId, String planItemId, String mediaItemId);

  /** 使用服务端累计专注秒数更新计划任务。 */
  void updateFocusProgress(
      String userId, String planItemId, long absoluteCompletedSeconds, boolean completed);

  /** 创建专注会话前校验任务所有权以及 FOCUS 欠债类型。 */
  void validateFocusLink(String userId, String planItemId);
}
