package com.shangan.ai.content.domain;

import com.shangan.common.api.BusinessException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

/** AI 生成但尚未进入正式题库的课程题目草稿。 */
public record QuizGenerationDraft(
    String id,
    String jobId,
    String courseId,
    String mediaItemId,
    Status status,
    int requestedQuestionCount,
    Instant createdAt,
    Instant publishedAt,
    List<Item> items) {

  public QuizGenerationDraft {
    items = items == null ? List.of() : List.copyOf(items);
  }

  /** 发布前完整校验；任一题目不合法时整批发布必须停止。 */
  public void validate() {
    if (items.isEmpty()) throw invalid("题目草稿不能为空");
    for (Item item : items) item.validate();
  }

  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "QUIZ_DRAFT_INVALID", message);
  }

  public enum Status {
    READY_FOR_REVIEW("待审核"),
    REJECTED("已驳回"),
    PUBLISHED("已发布");

    private final String label;

    Status(String label) {
      this.label = label;
    }

    /** 返回管理后台使用的中文草稿状态名称。 */
    public String label() {
      return label;
    }
  }

  public record Item(
      String id,
      String questionType,
      String content,
      String explanation,
      int sortOrder,
      String publishedQuestionId,
      List<Option> options) {
    public Item {
      options = options == null ? List.of() : List.copyOf(options);
    }

    public void validate() {
      if (content == null || content.isBlank()) throw invalidItem("题干不能为空");
      if (explanation == null || explanation.isBlank()) throw invalidItem("中文解析不能为空");
      if (!List.of("SINGLE_CHOICE", "TRUE_FALSE").contains(questionType)) {
        throw invalidItem("题型无效");
      }
      int minimum = "TRUE_FALSE".equals(questionType) ? 2 : 2;
      if (options.size() < minimum || ("TRUE_FALSE".equals(questionType) && options.size() != 2)) {
        throw invalidItem("题目选项数量无效");
      }
      if (options.stream()
          .anyMatch(option -> option.content() == null || option.content().isBlank())) {
        throw invalidItem("选项内容不能为空");
      }
      if (options.stream().filter(Option::correct).count() != 1) {
        throw invalidItem("每道题必须且只能有一个正确答案");
      }
    }

    private BusinessException invalidItem(String message) {
      return new BusinessException(HttpStatus.BAD_REQUEST, "QUIZ_DRAFT_INVALID", message);
    }
  }

  public record Option(String id, String content, boolean correct, int sortOrder) {}
}
