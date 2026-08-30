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

  void upsertMediaItem(MediaItem item, Instant now);

  void markUnavailableExcept(String courseId, List<String> availableEmbyIds, Instant now);

  void updateCourseSyncResult(String courseId, Instant syncedAt, String error);

  void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder);
}
