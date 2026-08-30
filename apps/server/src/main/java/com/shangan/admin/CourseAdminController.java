package com.shangan.admin;

import com.shangan.catalog.application.CourseSyncService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 管理员维护课程绑定、同步状态和本地课时控制。 */
@Controller
@Validated
public class CourseAdminController {

  private final CourseSyncService courses;

  public CourseAdminController(CourseSyncService courses) {
    this.courses = courses;
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
  String lessons(@PathVariable String courseId, Model model) {
    model.addAttribute("courseId", courseId);
    model.addAttribute("lessons", courses.listAdminLessons(courseId));
    return "admin/course-lessons";
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
