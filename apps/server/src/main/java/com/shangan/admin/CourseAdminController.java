package com.shangan.admin;

import com.shangan.catalog.application.CourseBatchService;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.application.LessonStudyContentImportService;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.common.api.BusinessException;
import com.shangan.quiz.application.QuizService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

/** 管理员维护课程绑定、同步状态和本地课时控制。 */
@Controller
@Validated
public class CourseAdminController {

  private final CourseSyncService courses;
  private final CourseBatchService courseBatches;
  private final LessonStudyContentImportService studyContents;
  private final QuizService quizzes;

  public CourseAdminController(
      CourseSyncService courses,
      CourseBatchService courseBatches,
      LessonStudyContentImportService studyContents,
      QuizService quizzes) {
    this.courses = courses;
    this.courseBatches = courseBatches;
    this.studyContents = studyContents;
    this.quizzes = quizzes;
  }

  @GetMapping("/admin/courses")
  String courses(Model model) {
    populateCoursesModel(model);
    return "admin/courses";
  }

  /** 供可键盘操作的来源选择器使用；响应只包含 Emby 安全元数据。 */
  @ResponseBody
  @GetMapping(value = "/admin/emby/sources", produces = MediaType.APPLICATION_JSON_VALUE)
  List<com.shangan.media.emby.EmbyDtos.MediaSource> searchSources(
      @RequestParam(defaultValue = "") String query) {
    return courseBatches.searchSources(query);
  }

  /** 一次提交最多 50 个来源；先全量验证，再创建或恢复，并逐门串行同步。 */
  @PostMapping("/admin/courses/batch")
  String batchAddCourses(
      @RequestParam(name = "sourceIds", required = false) List<String> sourceIds,
      Model model,
      HttpServletResponse response) {
    try {
      model.addAttribute("batchResult", courseBatches.addAndSynchronize(sourceIds));
    } catch (BusinessException exception) {
      response.setStatus(exception.status().value());
      model.addAttribute("batchError", exception.getMessage());
    }
    populateCoursesModel(model);
    return "admin/courses";
  }

  @PostMapping("/admin/courses")
  String createCourse(
      @RequestParam @NotBlank String name,
      @RequestParam(defaultValue = "") String description,
      @RequestParam(defaultValue = "") String selectedParentItemId,
      @RequestParam(defaultValue = "") String manualParentItemId) {
    courses.createCourse(
        name, description, preferredParentId(selectedParentItemId, manualParentItemId));
    return "redirect:/admin/courses";
  }

  @PostMapping("/admin/courses/{courseId}/sync")
  String sync(@PathVariable String courseId) {
    courses.syncCourse(courseId);
    return "redirect:/admin/courses/" + courseId + "/lessons";
  }

  /** 删除前展示课程名称、课时数量和历史保留说明，避免把归档误认为物理删除。 */
  @GetMapping("/admin/courses/{courseId}/archive")
  String archiveConfirmation(@PathVariable String courseId, Model model) {
    model.addAttribute("course", courses.getAdminCourse(courseId));
    model.addAttribute("lessonCount", courses.countAdminLessons(courseId));
    return "admin/course-archive";
  }

  /** 后台删除仅逻辑归档，不删除课时、计划、进度、欠债或内容数据。 */
  @PostMapping("/admin/courses/{courseId}/archive")
  String archive(@PathVariable String courseId) {
    courses.archiveCourse(courseId);
    return "redirect:/admin/courses?archived=1";
  }

  /** 已归档课程单独展示，并提供恢复原课程身份的操作。 */
  @GetMapping("/admin/courses/archived")
  String archivedCourses(Model model) {
    List<Course> archived = courses.listArchivedCourses();
    model.addAttribute("courses", archived);
    model.addAttribute("courseLessonCounts", archivedLessonCounts(archived));
    return "admin/course-archived";
  }

  @PostMapping("/admin/courses/{courseId}/restore")
  String restore(@PathVariable String courseId) {
    courses.restoreCourse(courseId);
    return "redirect:/admin/courses/archived?restored=1";
  }

  /** 打开媒体来源更换页；只展示媒体库安全元数据，不展示 Emby 物理路径。 */
  @GetMapping("/admin/courses/{courseId}/source")
  String source(@PathVariable String courseId, Model model) {
    populateSourceModel(courseId, model);
    return "admin/course-source";
  }

  /** 在事务外读取新来源并预览自动映射、新课时、失效课时和冲突。 */
  @PostMapping("/admin/courses/{courseId}/source/preview")
  String previewSource(
      @PathVariable String courseId,
      @RequestParam(defaultValue = "") String selectedParentItemId,
      @RequestParam(defaultValue = "") String manualParentItemId,
      Model model,
      HttpServletResponse response) {
    populateSourceModel(courseId, model);
    try {
      var preview =
          courses.previewSource(
              courseId, preferredParentId(selectedParentItemId, manualParentItemId));
      model.addAttribute("preview", preview);
    } catch (BusinessException exception) {
      response.setStatus(exception.status().value());
      model.addAttribute("sourceError", exception.getMessage());
    }
    return "admin/course-source";
  }

  /** 重新读取远端完整快照，并携带管理员对歧义项的一对一确认执行原子重绑。 */
  @PostMapping("/admin/courses/{courseId}/source")
  String rebindSource(
      @PathVariable String courseId,
      @RequestParam String targetParentItemId,
      @RequestParam(required = false) List<String> mapping) {
    courses.rebindCourse(courseId, targetParentItemId, confirmedMappings(mapping));
    return "redirect:/admin/courses/" + courseId + "/source?rebound=1";
  }

  @GetMapping("/admin/courses/{courseId}/lessons")
  String lessons(
      @PathVariable String courseId,
      @RequestParam(required = false) Integer imported,
      Model model) {
    populateLessonsModel(courseId, imported, null, model);
    return "admin/course-lessons";
  }

  /** 返回课时全文、摘要和正式题目数量的轻量快照，页面每两秒只局部更新三个状态列。 */
  @ResponseBody
  @GetMapping(
      value = "/admin/courses/{courseId}/lessons/live",
      produces = MediaType.APPLICATION_JSON_VALUE)
  LessonListLiveResponse lessonListLive(@PathVariable String courseId) {
    List<MediaItem> lessons = courses.listAdminLessons(courseId);
    var contentsByLessonId = studyContents.contentsByLessonId(courseId);
    Map<String, Integer> questionCounts = quizzes.adminQuestionCounts(courseId);
    return new LessonListLiveResponse(
        lessons.stream()
            .map(
                lesson -> {
                  var content = contentsByLessonId.get(lesson.id());
                  return new LessonLiveView(
                      lesson.id(),
                      content != null && content.transcriptReady(),
                      content != null && content.summaryReady(),
                      questionCounts.getOrDefault(lesson.id(), 0));
                })
            .toList());
  }

  /** 点击状态按钮时按需读取正文或正式题目，长全文不会进入周期轮询响应。 */
  @ResponseBody
  @GetMapping(
      value = "/admin/lessons/{lessonId}/preview",
      produces = MediaType.APPLICATION_JSON_VALUE)
  LessonPreviewResponse lessonPreview(@PathVariable String lessonId, @RequestParam String type) {
    MediaItem lesson = courses.getAdminLesson(lessonId);
    var content = studyContents.findByLessonId(lessonId).orElse(null);
    return switch (type) {
      case "transcript" ->
          new LessonPreviewResponse(
              lesson.title(),
              "transcript",
              "课时全文",
              content == null ? "" : content.fullText(),
              List.of());
      case "summary" ->
          new LessonPreviewResponse(
              lesson.title(),
              "summary",
              "课程摘要",
              content == null ? "" : content.summaryMarkdown(),
              List.of());
      case "questions" ->
          new LessonPreviewResponse(
              lesson.title(),
              "questions",
              "正式题目",
              "",
              quizzes.adminQuestions(lessonId).stream().map(QuestionPreview::from).toList());
      default ->
          throw new BusinessException(
              HttpStatus.BAD_REQUEST, "LESSON_PREVIEW_TYPE_INVALID", "不支持的课时预览类型");
    };
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
    model.addAttribute("questionCountsByLessonId", quizzes.adminQuestionCounts(courseId));
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

  private void populateCoursesModel(Model model) {
    model.addAttribute("courses", courses.listAdminCourses());
  }

  private void populateSourceModel(String courseId, Model model) {
    model.addAttribute("course", courses.getAdminCourse(courseId));
  }

  private Map<String, Integer> archivedLessonCounts(List<Course> archivedCourses) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Course course : archivedCourses) {
      counts.put(course.id(), courses.countAdminLessons(course.id()));
    }
    return counts;
  }

  private String preferredParentId(String selectedParentItemId, String manualParentItemId) {
    return manualParentItemId == null || manualParentItemId.isBlank()
        ? selectedParentItemId
        : manualParentItemId;
  }

  /** 解析页面的 remoteId|localId 值；非法项会被忽略并在重新规划时继续形成冲突。 */
  private Map<String, String> confirmedMappings(List<String> submitted) {
    Map<String, String> result = new LinkedHashMap<>();
    if (submitted == null) {
      return result;
    }
    for (String value : submitted) {
      String[] parts = value == null ? new String[0] : value.split("\\|", 2);
      if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
        result.put(parts[0], parts[1]);
      }
    }
    return result;
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

  /** 课时台账轮询响应，不包含正文和题目明细。 */
  private record LessonListLiveResponse(List<LessonLiveView> lessons) {}

  /** 单行课时最新状态。 */
  private record LessonLiveView(
      String id, boolean transcriptReady, boolean summaryReady, int questionCount) {}

  /** 弹窗预览响应按类型携带正文或题目，未使用的部分返回空值。 */
  private record LessonPreviewResponse(
      String lessonTitle,
      String type,
      String title,
      String content,
      List<QuestionPreview> questions) {}

  /** 管理员题目预览保留正确答案和解析，仅通过 ADMIN Session 返回。 */
  private record QuestionPreview(
      String questionType,
      String questionTypeLabel,
      String content,
      String explanation,
      boolean enabled,
      List<OptionPreview> options) {

    private static QuestionPreview from(com.shangan.quiz.domain.Question question) {
      return new QuestionPreview(
          question.questionType(),
          question.questionType().equals("TRUE_FALSE") ? "判断题" : "单选题",
          question.content(),
          question.explanation(),
          question.enabled(),
          question.options().stream()
              .map(option -> new OptionPreview(option.content(), option.correct()))
              .toList());
    }
  }

  /** 题目预览选项。 */
  private record OptionPreview(String content, boolean correct) {}
}
