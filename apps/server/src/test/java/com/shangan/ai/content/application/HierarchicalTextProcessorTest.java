package com.shangan.ai.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证超长课时全文会递归分层，且每次交给模型的正文均不超过预算。 */
class HierarchicalTextProcessorTest {

  @Test
  void recursivelyReducesLongTextWithinConservativeBudget() {
    var processor = new HierarchicalTextProcessor();
    String longText = "知识点一。知识点二。知识点三。\n\n".repeat(700);
    List<Integer> inputSizes = new ArrayList<>();

    String result =
        processor.process(
            longText,
            4096,
            1024,
            (chunk, finalStage) -> {
              inputSizes.add(chunk.length());
              return finalStage
                  ? "# 课时摘要\n最终摘要"
                  : chunk.substring(0, Math.min(120, chunk.length()));
            });

    int inputBudget = 4096 - 1024 - 2048;
    assertThat(inputSizes).allMatch(size -> size <= inputBudget);
    assertThat(inputSizes.size()).isGreaterThan(2);
    assertThat(result).startsWith("# 课时摘要");
  }

  @Test
  void stopsImmediatelyWhenOneRoundDoesNotShortenText() {
    var processor = new HierarchicalTextProcessor();
    String longText = "知识点。".repeat(3000);
    List<Integer> inputSizes = new ArrayList<>();

    // 每个分片都原样返回，归并后总长度不缩短；必须在第一轮判定不收敛。
    assertThatThrownBy(
            () ->
                processor.process(
                    longText,
                    4096,
                    1024,
                    (chunk, finalStage) -> {
                      inputSizes.add(chunk.length());
                      return chunk;
                    }))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error ->
                assertThat(((BusinessException) error).errorCode())
                    .isEqualTo("LLM_CONTEXT_BUDGET_INVALID"))
        .hasMessageContaining("收敛");
    assertThat(inputSizes).hasSize(2 * chunkCount(longText.length()));
  }

  /** 预算与实现一致：contextLength 减去输出与安全区后的可用字符数。 */
  private int chunkCount(int textLength) {
    int inputBudget = 4096 - 1024 - 2048;
    return (textLength + inputBudget - 1) / inputBudget;
  }
}
