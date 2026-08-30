package com.shangan.catalog.application;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 为 API 与后续计划模块提供稳定的课程只读接口。 */
@Service
public class CatalogQueryService {

  private final CourseRepository courses;

  public CatalogQueryService(CourseRepository courses) {
    this.courses = courses;
  }

  @Transactional(readOnly = true)
  public List<Course> listEnabledCourses() {
    return courses.findAllCourses(true);
  }

  @Transactional(readOnly = true)
  public Optional<Course> findCourse(String courseId) {
    return courses.findCourse(courseId).filter(Course::enabled);
  }

  @Transactional(readOnly = true)
  public List<MediaItem> listEnabledLessons(String courseId) {
    return courses.findMediaItems(courseId, true);
  }

  @Transactional(readOnly = true)
  public Optional<MediaItem> findLesson(String lessonId) {
    return courses.findMediaItem(lessonId).filter(MediaItem::enabled).filter(MediaItem::available);
  }
}
