package com.shangan.admin;

import com.shangan.ai.content.application.QuizDraftPublishService;
import com.shangan.ai.content.application.QuizDraftReviewService;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.common.api.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** 管理员审核 AI 题目草稿，并在课程范围执行原子批量发布。 */
@Controller
public class QuizDraftAdminController {

  private final QuizDraftReviewService reviews;
  private final QuizDraftPublishService publishing;
  private final CourseSyncService courses;

  public QuizDraftAdminController(
      QuizDraftReviewService reviews,
      QuizDraftPublishService publishing,
      CourseSyncService courses) {
    this.reviews = reviews;
    this.publishing = publishing;
    this.courses = courses;
  }

  @GetMapping("/admin/courses/{courseId}/quiz-drafts")
  String list(
      @PathVariable String courseId,
      @RequestParam(defaultValue = "false") boolean published,
      Model model) {
    model.addAttribute("course", courses.getAdminCourse(courseId));
    model.addAttribute("drafts", reviews.list(courseId));
    // 审核页使用课时名称作为主标题，内部 UUID 只用于请求参数，不直接暴露给管理员阅读。
    var lessonsById = new LinkedHashMap<String, MediaItem>();
    for (MediaItem lesson : courses.listAdminLessons(courseId)) {
      lessonsById.put(lesson.id(), lesson);
    }
    model.addAttribute("lessonsById", lessonsById);
    model.addAttribute("published", published);
    return "admin/quiz-drafts";
  }

  @PostMapping("/admin/courses/{courseId}/quiz-drafts/publish")
  String publish(
      @PathVariable String courseId, @RequestParam(name = "draftId") List<String> draftIds) {
    publishing.publish(courseId, draftIds);
    return "redirect:/admin/courses/" + courseId + "/quiz-drafts?published=true";
  }

  /** 课程草稿批量通过、驳回或删除，AJAX 调用不会重新渲染整个管理页面。 */
  @ResponseBody
  @PostMapping(
      value = "/admin/courses/{courseId}/quiz-drafts/batch",
      produces = MediaType.APPLICATION_JSON_VALUE)
  BatchReviewResponse batch(
      @PathVariable String courseId,
      @RequestParam String action,
      @RequestParam(name = "draftId", required = false) List<String> draftIds) {
    return switch (action) {
      case "APPROVE" -> {
        var result = publishing.publish(courseId, draftIds);
        yield new BatchReviewResponse(
            true,
            "已通过并发布 " + result.publishedDraftCount() + " 份草稿",
            action,
            draftIds == null ? List.of() : List.copyOf(draftIds));
      }
      case "REJECT" -> {
        int count = reviews.reject(courseId, draftIds);
        yield new BatchReviewResponse(
            true,
            "已驳回 " + count + " 份草稿",
            action,
            draftIds == null ? List.of() : List.copyOf(draftIds));
      }
      case "DELETE" -> {
        int count = reviews.deleteDrafts(courseId, draftIds);
        yield new BatchReviewResponse(
            true,
            "已删除 " + count + " 份草稿",
            action,
            draftIds == null ? List.of() : List.copyOf(draftIds));
      }
      default ->
          throw new BusinessException(
              HttpStatus.BAD_REQUEST, "QUIZ_DRAFT_ACTION_INVALID", "不支持的题目草稿批量操作");
    };
  }

  @PostMapping("/admin/courses/{courseId}/quiz-drafts/{draftId}/items/{itemId}")
  String updateItem(
      @PathVariable String courseId,
      @PathVariable String draftId,
      @PathVariable String itemId,
      @RequestParam String questionType,
      @RequestParam String content,
      @RequestParam String explanation,
      @RequestParam int sortOrder,
      @RequestParam(name = "optionContent") List<String> optionContents,
      @RequestParam int correctOptionIndex) {
    reviews.updateItem(
        courseId,
        draftId,
        itemId,
        questionType,
        content,
        explanation,
        sortOrder,
        optionContents,
        correctOptionIndex);
    return "redirect:/admin/courses/" + courseId + "/quiz-drafts";
  }

  @PostMapping("/admin/courses/{courseId}/quiz-drafts/{draftId}/items/{itemId}/delete")
  String deleteItem(
      @PathVariable String courseId, @PathVariable String draftId, @PathVariable String itemId) {
    reviews.deleteItem(courseId, draftId, itemId);
    return "redirect:/admin/courses/" + courseId + "/quiz-drafts";
  }

  /** 草稿审核 AJAX 响应，只返回本次操作涉及的草稿标识。 */
  private record BatchReviewResponse(
      boolean success, String message, String action, List<String> draftIds) {}
}
