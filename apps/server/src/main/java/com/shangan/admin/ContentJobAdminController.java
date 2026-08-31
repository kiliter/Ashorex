package com.shangan.admin;

import com.shangan.ai.content.application.ContentGenerationJobService;
import com.shangan.ai.content.domain.ContentGenerationJob;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.application.LessonStudyContentImportService;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 管理员创建、筛选、查看和重试课程内容任务。 */
@Controller
public class ContentJobAdminController {

  private final ContentGenerationJobService jobs;
  private final CourseSyncService courses;
  private final LessonStudyContentImportService contents;

  public ContentJobAdminController(
      ContentGenerationJobService jobs,
      CourseSyncService courses,
      LessonStudyContentImportService contents) {
    this.jobs = jobs;
    this.courses = courses;
    this.contents = contents;
  }

  @GetMapping("/admin/content-jobs")
  String list(
      @RequestParam(defaultValue = "") String courseId,
      @RequestParam(defaultValue = "") String type,
      @RequestParam(defaultValue = "") String status,
      Model model) {
    model.addAttribute("jobs", jobs.recent(courseId, type, status, 300));
    model.addAttribute("courses", courses.listAdminCourses());
    model.addAttribute("selectedCourseId", courseId);
    model.addAttribute("selectedType", type);
    model.addAttribute("selectedStatus", status);
    model.addAttribute("stats", jobs.stats());
    return "admin/content-jobs";
  }

  @GetMapping("/admin/content-jobs/{jobId}")
  String detail(@PathVariable String jobId, Model model) {
    ContentGenerationJob job = jobs.detail(jobId);
    model.addAttribute("job", job);
    model.addAttribute("course", courses.getAdminCourse(job.courseId()));
    model.addAttribute("lesson", courses.getAdminLesson(job.mediaItemId()));
    model.addAttribute("logs", jobs.logs(jobId));
    return "admin/content-job-detail";
  }

  @PostMapping("/admin/content-jobs/{jobId}/retry")
  String retry(@PathVariable String jobId, Principal principal) {
    String newJobId = jobs.retry(jobId, principal.getName()).jobId();
    return "redirect:/admin/content-jobs/" + newJobId;
  }

  @PostMapping("/admin/lessons/{lessonId}/transcribe")
  String transcribeLesson(
      @PathVariable String lessonId,
      @RequestParam(defaultValue = "false") boolean overwrite,
      Principal principal) {
    return enqueueLesson(
        lessonId, ContentGenerationJob.Type.TRANSCRIBE, overwrite, 5, principal.getName());
  }

  @PostMapping("/admin/lessons/{lessonId}/summarize")
  String summarizeLesson(
      @PathVariable String lessonId,
      @RequestParam(defaultValue = "false") boolean overwrite,
      Principal principal) {
    return enqueueLesson(
        lessonId, ContentGenerationJob.Type.SUMMARIZE, overwrite, 5, principal.getName());
  }

  @PostMapping("/admin/lessons/{lessonId}/generate-quiz")
  String generateQuizForLesson(
      @PathVariable String lessonId,
      @RequestParam(defaultValue = "5") int questionCount,
      Principal principal) {
    return enqueueLesson(
        lessonId,
        ContentGenerationJob.Type.GENERATE_QUIZ,
        false,
        questionCount,
        principal.getName());
  }

  @PostMapping("/admin/courses/{courseId}/transcribe")
  String transcribeCourse(@PathVariable String courseId, Principal principal) {
    jobs.enqueueCourse(courseId, ContentGenerationJob.Type.TRANSCRIBE, 5, principal.getName());
    return "redirect:/admin/courses/" + courseId + "/lessons";
  }

  @PostMapping("/admin/courses/{courseId}/summarize")
  String summarizeCourse(@PathVariable String courseId, Principal principal) {
    jobs.enqueueCourse(courseId, ContentGenerationJob.Type.SUMMARIZE, 5, principal.getName());
    return "redirect:/admin/courses/" + courseId + "/lessons";
  }

  @PostMapping("/admin/courses/{courseId}/generate-quiz")
  String generateQuizForCourse(
      @PathVariable String courseId,
      @RequestParam(defaultValue = "5") int questionCount,
      Principal principal) {
    jobs.enqueueCourse(
        courseId, ContentGenerationJob.Type.GENERATE_QUIZ, questionCount, principal.getName());
    return "redirect:/admin/courses/" + courseId + "/quiz-drafts";
  }

  @GetMapping("/admin/lessons/{lessonId}/study-content")
  String studyContent(@PathVariable String lessonId, Model model) {
    var lesson = courses.getAdminLesson(lessonId);
    model.addAttribute("lesson", lesson);
    model.addAttribute("course", courses.getAdminCourse(lesson.courseId()));
    model.addAttribute("content", contents.findByLessonId(lessonId).orElse(null));
    return "admin/lesson-study-content";
  }

  private String enqueueLesson(
      String lessonId,
      ContentGenerationJob.Type type,
      boolean overwrite,
      int questionCount,
      String principal) {
    var result = jobs.enqueueLesson(lessonId, type, overwrite, questionCount, principal);
    return result.created()
        ? "redirect:/admin/content-jobs/" + result.jobId()
        : "redirect:/admin/content-jobs";
  }
}
