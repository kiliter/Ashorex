package com.shangan.catalog.application;

import java.util.List;
import java.util.Map;

/**
 * 课程模块读取「某用户在某些资源上的学习状态」的显式端口。
 *
 * <p>写入方在 todo 模块，这里只做跨模块只读访问，避免课程库直接依赖 Todo 表。
 */
public interface ResourceProgressPort {

  /** 批量读取该用户在给定资源上的累计状态；缺失的资源不出现在返回值中。 */
  Map<String, ResourceProgress> progressOf(String userId, List<String> resourceIds);

  /** 该用户在某课程全部资源上的累计状态。 */
  Map<String, ResourceProgress> progressOfCourse(String userId, String courseId);

  /** 跨 Todo 的资源累计状态。 */
  record ResourceProgress(
      long maxPositionMs, int maxPositionPage, long totalWatchedMs, int completedCount) {

    public static ResourceProgress empty() {
      return new ResourceProgress(0L, 0, 0L, 0);
    }

    public boolean completedAtLeastOnce() {
      return completedCount > 0;
    }
  }
}
