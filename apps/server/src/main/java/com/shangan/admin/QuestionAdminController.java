package com.shangan.admin;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.common.api.BusinessException;
import com.shangan.quiz.application.QuizService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 管理员维护单选题和判断题；所有约束最终由 QuizService 统一校验。 */
@Controller
public class QuestionAdminController {
  private final QuizService quizzes;
  private final CatalogQueryService catalog;

  public QuestionAdminController(QuizService quizzes, CatalogQueryService catalog) {
    this.quizzes = quizzes;
    this.catalog = catalog;
  }

  @GetMapping("/admin/lessons/{lessonId}/questions")
  String questions(@PathVariable String lessonId, Model model) {
    model.addAttribute("lesson", requireLesson(lessonId));
    model.addAttribute("questions", quizzes.adminQuestions(lessonId));
    return "admin/questions";
  }

  @GetMapping("/admin/lessons/{lessonId}/questions/new")
  String createForm(@PathVariable String lessonId, Model model) {
    model.addAttribute("lesson", requireLesson(lessonId));
    model.addAttribute("question", null);
    return "admin/question-form";
  }

  @GetMapping("/admin/lessons/{lessonId}/questions/{questionId}")
  String editForm(@PathVariable String lessonId, @PathVariable String questionId, Model model) {
    var question = quizzes.adminQuestion(questionId);
    if (!lessonId.equals(question.mediaItemId())) throw questionNotFound();
    model.addAttribute("lesson", requireLesson(lessonId));
    model.addAttribute("question", question);
    return "admin/question-form";
  }

  @PostMapping("/admin/lessons/{lessonId}/questions/save")
  String save(
      @PathVariable String lessonId,
      @RequestParam(required = false) String questionId,
      @RequestParam String questionType,
      @RequestParam String content,
      @RequestParam(defaultValue = "") String explanation,
      @RequestParam(defaultValue = "false") boolean enabled,
      @RequestParam int sortOrder,
      @RequestParam(required = false) List<String> optionId,
      @RequestParam List<String> optionContent,
      @RequestParam int correctOption) {
    requireLesson(lessonId);
    if (correctOption < 0
        || correctOption >= optionContent.size()
        || optionContent.get(correctOption).isBlank()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "QUIZ_QUESTION_INVALID", "正确选项序号无效");
    }
    List<QuizService.AdminOptionCommand> options = new ArrayList<>();
    for (int index = 0; index < optionContent.size(); index++) {
      if (optionContent.get(index).isBlank()) continue;
      String id = optionId != null && index < optionId.size() ? optionId.get(index) : null;
      options.add(
          new QuizService.AdminOptionCommand(
              id, optionContent.get(index), index == correctOption, options.size()));
    }
    quizzes.saveQuestion(
        new QuizService.AdminQuestionCommand(
            questionId, lessonId, questionType, content, explanation, enabled, sortOrder, options));
    return "redirect:/admin/lessons/" + lessonId + "/questions";
  }

  private Object requireLesson(String lessonId) {
    return catalog.findLesson(lessonId).orElseThrow(this::questionNotFound);
  }

  private BusinessException questionNotFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, "LESSON_NOT_FOUND", "课时不存在");
  }
}
