package com.shangan.quiz.domain;

import com.shangan.common.api.BusinessException;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** 单选或判断题聚合；选项正确性仅在服务端领域对象中存在。 */
public record Question(
    String id,
    String mediaItemId,
    String questionType,
    String content,
    String explanation,
    boolean enabled,
    int sortOrder,
    List<Option> options) {

  public Question {
    options = options == null ? List.of() : List.copyOf(options);
  }

  /** 管理端保存前执行完整题目定义校验。 */
  public void validate() {
    if (content == null || content.isBlank()) throw invalid("题目内容不能为空");
    if (!List.of("SINGLE_CHOICE", "TRUE_FALSE").contains(questionType)) {
      throw invalid("题型无效");
    }
    int expectedCount = questionType.equals("TRUE_FALSE") ? 2 : Math.max(2, options.size());
    if (options.size() != expectedCount
        || options.stream().anyMatch(o -> o.content() == null || o.content().isBlank())) {
      throw invalid(questionType.equals("TRUE_FALSE") ? "判断题必须有两个选项" : "单选题至少需要两个选项");
    }
    if (options.stream().map(Option::id).filter(Objects::nonNull).distinct().count()
        != options.size()) {
      throw invalid("题目选项标识不能重复");
    }
    if (options.stream().filter(Option::correct).count() != 1) {
      throw invalid("每道题必须且只能有一个正确选项");
    }
  }

  /** 所选选项必须属于当前题目，否则按非法提交处理。 */
  public boolean correct(String selectedOptionId) {
    return options.stream()
        .filter(option -> option.id().equals(selectedOptionId))
        .findFirst()
        .orElseThrow(() -> invalid("所选答案不属于当前题目"))
        .correct();
  }

  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "QUIZ_QUESTION_INVALID", message);
  }

  /** 题目选项按 sortOrder 和 ID 确定性展示。 */
  public record Option(String id, String content, boolean correct, int sortOrder) {}
}
