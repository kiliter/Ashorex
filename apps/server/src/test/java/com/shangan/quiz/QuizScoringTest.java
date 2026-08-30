package com.shangan.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.quiz.domain.Question;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证两种 V1 题型的精确判分和后台题目定义校验。 */
class QuizScoringTest {
  @Test
  void scoresSingleChoiceAndTrueFalseExactly() {
    Question single = question("SINGLE_CHOICE", List.of(option("a", false), option("b", true)));
    Question truth = question("TRUE_FALSE", List.of(option("true", true), option("false", false)));

    assertThat(single.correct("b")).isTrue();
    assertThat(single.correct("a")).isFalse();
    assertThat(truth.correct("true")).isTrue();
    assertThat(truth.correct("false")).isFalse();
  }

  @Test
  void rejectsInvalidOptionDefinitions() {
    assertThatThrownBy(
            () ->
                question("SINGLE_CHOICE", List.of(option("a", true), option("b", true))).validate())
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> question("TRUE_FALSE", List.of(option("true", true))).validate())
        .isInstanceOf(BusinessException.class);
  }

  private Question question(String type, List<Question.Option> options) {
    return new Question("q-1", "media-1", type, "题目", "解析", true, 0, options);
  }

  private Question.Option option(String id, boolean correct) {
    return new Question.Option(id, id, correct, 0);
  }
}
