package com.shangan.ai.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 验证 AI 出题结构校验、唯一答案规则和一次格式修复。 */
class QuizContentGeneratorTest {

  @Test
  void acceptsValidSingleChoiceAndTrueFalseQuestions() {
    var model = new QueueModel(validQuestions());
    var generator =
        new QuizContentGenerator(new HierarchicalTextProcessor(), model, new ObjectMapper());

    QuizContentGenerator.Generation result = generator.generate("课时全文", "# 摘要", 2, llmSettings());

    assertThat(result.questions()).hasSize(2);
    assertThat(result.questions().get(0).type()).isEqualTo("SINGLE_CHOICE");
    assertThat(result.questions().get(1).type()).isEqualTo("TRUE_FALSE");
    assertThat(result.questions())
        .allSatisfy(
            question ->
                assertThat(question.options().stream().filter(option -> option.correct()).count())
                    .isEqualTo(1));
  }

  @Test
  void repairsMalformedResponseOnlyOnce() {
    var model = new QueueModel("not-json", validQuestions());
    var generator =
        new QuizContentGenerator(new HierarchicalTextProcessor(), model, new ObjectMapper());

    QuizContentGenerator.Generation result = generator.generate("课时全文", null, 2, llmSettings());

    assertThat(result.questions()).hasSize(2);
    assertThat(model.calls).isEqualTo(2);
  }

  @Test
  void rejectsInvalidQuestionTypeAfterRepair() {
    String invalid =
        """
        {"questions":[{"type":"ESSAY","content":"题干","explanation":"中文解析",\
        "options":[{"content":"A","correct":true},{"content":"B","correct":false}]}]}
        """;
    var model = new QueueModel(invalid, invalid);
    var generator =
        new QuizContentGenerator(new HierarchicalTextProcessor(), model, new ObjectMapper());

    assertThatThrownBy(() -> generator.generate("课时全文", null, 1, llmSettings()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("QUIZ_GENERATION_FAILED"));
    assertThat(model.calls).isEqualTo(2);
  }

  private RuntimeIntegrationSettings.Llm llmSettings() {
    return new RuntimeIntegrationSettings.Llm(
        "http://llm.local/v1", "secret", "provider/model", 16_384, 2_048, 60);
  }

  private String validQuestions() {
    return """
        {"questions":[
          {"type":"SINGLE_CHOICE","content":"第一题","explanation":"第一题中文解析",\
           "options":[{"content":"选项 A","correct":true},{"content":"选项 B","correct":false}]},
          {"type":"TRUE_FALSE","content":"第二题","explanation":"第二题中文解析",\
           "options":[{"content":"正确","correct":false},{"content":"错误","correct":true}]}
        ]}
        """;
  }

  /** 按顺序返回测试响应，同时记录调用次数，便于证明最多只修复一次。 */
  private static final class QueueModel implements ContentLanguageModel {
    private final Queue<String> responses = new ArrayDeque<>();
    private int calls;

    private QueueModel(String... responses) {
      this.responses.addAll(java.util.List.of(responses));
    }

    @Override
    public GenerationResult generate(
        String systemPrompt,
        String userPrompt,
        RuntimeIntegrationSettings.Llm configuration,
        boolean jsonResponse) {
      calls++;
      return new GenerationResult(responses.remove(), 10, 5);
    }
  }
}
