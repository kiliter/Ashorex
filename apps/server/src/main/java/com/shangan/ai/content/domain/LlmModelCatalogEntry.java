package com.shangan.ai.content.domain;

import java.time.Instant;

/** 从 OpenRouter 缓存的模型上下文元数据，不包含任何推理密钥。 */
public record LlmModelCatalogEntry(
    String modelId,
    String displayName,
    int contextLength,
    int maxCompletionTokens,
    String tokenizer,
    String supportedParametersJson,
    Instant fetchedAt,
    boolean active) {}
