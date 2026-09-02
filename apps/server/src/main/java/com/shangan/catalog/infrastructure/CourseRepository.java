package com.shangan.catalog.infrastructure;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 课程和课时快照的持久化边界。 */
public interface CourseRepository {
  Optional<Course> findCourse(String courseId);

  List<Course> findAllCourses(boolean enabledOnly);

  List<MediaItem> findMediaItems(String courseId, boolean enabledOnly);

  Optional<MediaItem> findMediaItem(String mediaItemId);

  void insertCourse(Course course, Instant now);

  void insertMediaItem(MediaItem item, Instant now);

  void updateMediaItemFromRemote(MediaItem item, Instant now);

  void insertMediaItemSourceMapping(
      String id,
      String mediaItemId,
      String oldEmbyItemId,
      String newEmbyItemId,
      String matchType,
      Instant now);

  void markUnavailableExceptMediaIds(
      String courseId, List<String> availableMediaItemIds, Instant now);

  void updateCourseSource(String courseId, String embyParentItemId, Instant now);

  /** 逻辑归档或恢复课程，只改变可见性，不删除课程与任何学习历史。 */
  void updateCourseEnabled(String courseId, boolean enabled, Instant now);

  /** 将已归档课程从后台归档区域移除，底层课程与学习数据保持不变。 */
  default void updateCourseRemoved(String courseId, Instant now) {
    throw new UnsupportedOperationException("课程仓储尚未实现归档移除");
  }

  /** 为课程移除操作写入不含媒体路径和密钥的安全审计。 */
  default void insertCourseRemovalAudit(
      String id, String courseId, String administrator, String requestId, Instant now) {
    throw new UnsupportedOperationException("课程仓储尚未实现移除审计");
  }

  void updateCourseSyncResult(String courseId, Instant syncedAt, String error);

  void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder);
}
