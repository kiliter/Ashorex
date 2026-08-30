package com.shangan.catalog.api;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.common.api.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** iOS 客户端读取本地课程快照的 API。 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

  private final CatalogQueryService catalog;

  public CatalogController(CatalogQueryService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/courses")
  List<CourseResponse> courses() {
    return catalog.listEnabledCourses().stream().map(CourseResponse::from).toList();
  }

  @GetMapping("/courses/{courseId}")
  CourseDetailResponse course(@PathVariable String courseId) {
    Course course =
        catalog
            .findCourse(courseId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
    return new CourseDetailResponse(
        course.id(),
        course.name(),
        course.description(),
        catalog.listEnabledLessons(course.id()).stream().map(LessonResponse::from).toList());
  }

  @GetMapping("/lessons/{lessonId}")
  LessonResponse lesson(@PathVariable String lessonId) {
    return catalog
        .findLesson(lessonId)
        .map(LessonResponse::from)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "LESSON_NOT_FOUND", "课时不存在"));
  }

  record CourseResponse(String id, String name, String description) {
    static CourseResponse from(Course course) {
      return new CourseResponse(course.id(), course.name(), course.description());
    }
  }

  record CourseDetailResponse(
      String id, String name, String description, List<LessonResponse> lessons) {}

  record LessonResponse(String id, String courseId, String title, long durationMs, int sortOrder) {
    static LessonResponse from(MediaItem item) {
      return new LessonResponse(
          item.id(), item.courseId(), item.title(), item.durationMs(), item.sortOrder());
    }
  }
}
