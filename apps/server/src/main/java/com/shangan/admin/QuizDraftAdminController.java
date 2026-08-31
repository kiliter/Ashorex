package com.shangan.admin;

import com.shangan.ai.content.application.QuizDraftPublishService;
import com.shangan.ai.content.application.QuizDraftReviewService;
import com.shangan.catalog.application.CourseSyncService;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
    model.addAttribute("published", published);
    return "admin/quiz-drafts";
  }

  @PostMapping("/admin/courses/{courseId}/quiz-drafts/publish")
  String publish(
      @PathVariable String courseId, @RequestParam(name = "draftId") List<String> draftIds) {
    publishing.publish(courseId, draftIds);
    return "redirect:/admin/courses/" + courseId + "/quiz-drafts?published=true";
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
}
