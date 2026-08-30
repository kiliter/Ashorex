package com.shangan.planning.application;

/** 锁定计划时查询视频是否有必答题，并将结果冻结到计划任务。 */
public interface VideoTaskRequirementPort {
  boolean quizRequired(String mediaItemId);
}
