package com.shangan.ai.content.application;

import com.shangan.common.api.BusinessException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 按保守字符预算切片并递归归并，避免长课时一次塞入模型上下文。 */
@Component
public class HierarchicalTextProcessor {

  private static final int SAFETY_TOKENS = 2048;
  private static final int MAX_DEPTH = 12;

  /** Tokenizer 无法可靠复现时按“一字符最多占一个 Token”估算。安全区还覆盖系统指令、分隔符和协议开销。 */
  public String process(
      String text, int contextLength, int maxCompletionTokens, StageGenerator generator) {
    if (text == null || text.isBlank()) throw invalid("待处理全文不能为空");
    int inputBudget = contextLength - maxCompletionTokens - SAFETY_TOKENS;
    if (inputBudget < 256) throw invalid("模型上下文不足，无法为输出和安全区预留空间");

    List<String> chunks = split(text.trim(), inputBudget);
    if (chunks.size() == 1) return requireOutput(generator.generate(chunks.getFirst(), true));

    String reduced = reduce(chunks, generator);
    for (int depth = 1; depth <= MAX_DEPTH; depth++) {
      List<String> reducedChunks = split(reduced, inputBudget);
      if (reducedChunks.size() == 1) {
        return requireOutput(generator.generate(reducedChunks.getFirst(), true));
      }
      String next = reduce(reducedChunks, generator);
      if (next.length() >= reduced.length() && depth == MAX_DEPTH) {
        throw invalid("分层归并未能在上下文预算内收敛");
      }
      reduced = next;
    }
    throw invalid("分层归并层数超过安全上限");
  }

  private String reduce(List<String> chunks, StageGenerator generator) {
    List<String> outputs = new ArrayList<>();
    for (String chunk : chunks) {
      outputs.add(requireOutput(generator.generate(chunk, false)));
    }
    return String.join("\n\n", outputs);
  }

  /** 优先在段落、换行和中文句号处分割，极端长句才按字符硬切。 */
  List<String> split(String text, int maximumChars) {
    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + maximumChars);
      if (end < text.length()) {
        int boundary = preferredBoundary(text, start, end);
        if (boundary > start) end = boundary;
      }
      String chunk = text.substring(start, end).trim();
      if (!chunk.isEmpty()) chunks.add(chunk);
      start = end;
      while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
    }
    return List.copyOf(chunks);
  }

  private int preferredBoundary(String text, int start, int end) {
    int minimum = start + Math.max(1, (end - start) / 3);
    for (String marker : List.of("\n\n", "\n", "。", "！", "？", ". ", "; ")) {
      int index = text.lastIndexOf(marker, end - 1);
      if (index >= minimum) return index + marker.length();
    }
    return end;
  }

  private String requireOutput(String output) {
    if (output == null || output.isBlank()) throw invalid("模型返回空内容");
    return output.trim();
  }

  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "LLM_CONTEXT_BUDGET_INVALID", message);
  }

  /** 每个阶段只接收预算内文本，finalStage 表示当前请求必须输出最终结果。 */
  @FunctionalInterface
  public interface StageGenerator {
    String generate(String text, boolean finalStage);
  }
}
