package com.shangan.ai.content.application;

import com.shangan.common.integration.RuntimeIntegrationSettings;

/** 摘要与出题共用的最小 LLM 边界，便于长文本处理器独立测试。 */
public interface ContentLanguageModel {

  GenerationResult generate(
      String systemPrompt,
      String userPrompt,
      RuntimeIntegrationSettings.Llm configuration,
      boolean jsonResponse);

  /** 只保存安全的输出正文和上游 usage 计数，不保存完整 Prompt。 */
  record GenerationResult(String text, int promptTokens, int completionTokens) {}
}
