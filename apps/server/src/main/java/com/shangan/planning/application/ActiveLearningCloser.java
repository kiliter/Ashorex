package com.shangan.planning.application;

import java.time.Instant;

/** 计划关闭时由学习模块实现，用于结束仍在进行的会话。 */
public interface ActiveLearningCloser {
  void closeForPlan(String userId, String planId, Instant closedAt);
}
