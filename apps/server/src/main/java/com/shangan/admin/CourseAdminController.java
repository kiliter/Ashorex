package com.shangan.admin;

import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.application.LessonStudyContentImportService;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.common.api.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/** 管理员维护课程绑定、同步状态和本地课时控制。 */
@Controller
@Validated
public class CourseAdminController {

  private final CourseSyncService courses;
  private final LessonStudyContentImportService studyContents;

  public CourseAdminController(
      CourseSyncService courses, LessonStudyContentImportService studyContents) {
    this.courses = courses;
    this.studyContents = studyContents;
  }

  @GetMapping("/admin/courses")
  String courses(Model model) {
    model.addAttribute("courses", courses.listAdminCourses());
    return "admin/courses";
  }

  @PostMapping("/admin/courses")
  String createCourse(
      @RequestParam @NotBlank String name,
      @RequestParam(defaultValue = "") String description,
      @RequestParam @NotBlank String embyParentItemId) {
    courses.createCourse(name, description, embyParentItemId);
    return "redirect:/admin/courses";
  }

  @PostMapping("/admin/courses/{courseId}/sync")
  String sync(@PathVariable String courseId) {
    courses.syncCourse(courseId);
    return "redirect:/admin/courses/" + courseId + "/lessons";
  }

  @GetMapping("/admin/courses/{courseId}/lessons")
  String lessons(
      @PathVariable String courseId,
      @RequestParam(required = false) Integer imported,
      Model model) {
    populateLessonsModel(courseId, imported, null, model);
    return "admin/course-lessons";
  }

  /** 接收一门课程的完整学习内容 ZIP；业务校验失败时原页显示安全原因。 */
  @PostMapping("/admin/courses/{courseId}/study-content/import")
  String importStudyContent(
      @PathVariable String courseId,
      @RequestParam("file") MultipartFile file,
      Model model,
      HttpServletResponse response) {
    try {
      int imported = studyContents.importZip(courseId, file.getBytes()).importedCount();
      return "redirect:/admin/courses/" + courseId + "/lessons?imported=" + imported;
    } catch (BusinessException exception) {
      response.setStatus(exception.status().value());
      populateLessonsModel(courseId, null, exception.getMessage(), model);
      return "admin/course-lessons";
    } catch (IOException exception) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      populateLessonsModel(courseId, null, "ZIP 文件无法读取，请重新选择文件", model);
      return "admin/course-lessons";
    }
  }

  /** 统一构造列表与导入反馈模型，保证 GET 和错误回显使用相同页面数据。 */
  private void populateLessonsModel(
      String courseId, Integer imported, String importError, Model model) {
    List<MediaItem> lessons = courses.listAdminLessons(courseId);
    Map<String, Instant> contentUpdatedAtByLessonId =
        studyContents.contentUpdatedAtByLessonId(courseId);
    var contentsByLessonId = studyContents.contentsByLessonId(courseId);
    model.addAttribute("courseId", courseId);
    model.addAttribute("course", courses.getAdminCourse(courseId));
    model.addAttribute("lessons", lessons);
    model.addAttribute("studyContentUpdatedAtByLessonId", contentUpdatedAtByLessonId);
    model.addAttribute("studyContentsByLessonId", contentsByLessonId);
    model.addAttribute("lessonCount", lessons.size());
    model.addAttribute("enabledLessonCount", lessons.stream().filter(MediaItem::enabled).count());
    model.addAttribute("contentImportedCount", contentUpdatedAtByLessonId.size());
    model.addAttribute(
        "transcriptReadyCount",
        contentsByLessonId.values().stream().filter(value -> value.transcriptReady()).count());
    model.addAttribute(
        "summaryReadyCount",
        contentsByLessonId.values().stream().filter(value -> value.summaryReady()).count());
    model.addAttribute("imported", imported);
    model.addAttribute("importError", importError);
  }

  @PostMapping("/admin/courses/{courseId}/lessons/{lessonId}")
  String updateLesson(
      @PathVariable String courseId,
      @PathVariable String lessonId,
      @RequestParam(defaultValue = "false") boolean enabled,
      @RequestParam int sortOrder) {
    courses.updateLessonControls(lessonId, enabled, sortOrder);
    return "redirect:/admin/courses/" + courseId + "/lessons";
  }
}
