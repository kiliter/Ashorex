package com.shangan.catalog.api;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.application.LessonStudyContentImportService;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LessonStudyContent;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.common.api.BusinessException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
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
  private final LessonStudyContentImportService studyContents;

  public CatalogController(
      CatalogQueryService catalog, LessonStudyContentImportService studyContents) {
    this.catalog = catalog;
    this.studyContents = studyContents;
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

  /** 返回一集已经就绪的全文或 Markdown 摘要，不在读取请求中调用外部服务。 */
  @GetMapping("/lessons/{lessonId}/study-content")
  LessonStudyContentResponse studyContent(@PathVariable String lessonId) {
    catalog
        .findLesson(lessonId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "LESSON_NOT_FOUND", "课时不存在"));
    LessonStudyContent content =
        studyContents
            .findByLessonId(lessonId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "LESSON_STUDY_CONTENT_NOT_FOUND", "该课时尚无学习内容"));
    return LessonStudyContentResponse.from(content);
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

  /** App 读取的课程学习内容直接 DTO。 */
  record LessonStudyContentResponse(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lessonId,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {"READY", "MISSING"})
          String transcriptStatus,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {"READY", "MISSING"})
          String summaryStatus,
      @Schema(nullable = true) String fullText,
      @Schema(nullable = true) String summaryMarkdown,
      @Schema(nullable = true) Instant transcriptUpdatedAt,
      @Schema(nullable = true) Instant summaryUpdatedAt,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {
    static LessonStudyContentResponse from(LessonStudyContent content) {
      return new LessonStudyContentResponse(
          content.mediaItemId(),
          content.transcriptReady() ? "READY" : "MISSING",
          content.summaryReady() ? "READY" : "MISSING",
          content.fullText(),
          content.summaryMarkdown(),
          content.transcriptUpdatedAt(),
          content.summaryUpdatedAt(),
          content.updatedAt());
    }
  }
}
