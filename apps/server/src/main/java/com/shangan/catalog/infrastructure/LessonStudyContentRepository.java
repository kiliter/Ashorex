package com.shangan.catalog.infrastructure;

import com.shangan.catalog.domain.LessonStudyContent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 课程学习内容的持久化边界。 */
public interface LessonStudyContentRepository {

  Optional<LessonStudyContent> findByMediaItemId(String mediaItemId);

  Map<String, Instant> findUpdatedAtByCourseId(String courseId);

  long count();

  void upsertAll(List<LessonStudyContent> contents);
}
