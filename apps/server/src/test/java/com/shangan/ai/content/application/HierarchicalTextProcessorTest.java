package com.shangan.ai.content.application;

import static org.assertj.core.api.Assertions.assertThat;

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
}
