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

  void updateCourseSyncResult(String courseId, Instant syncedAt, String error);

  void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder);
}
