package com.shangan.ai.content.infrastructure;

import com.shangan.ai.content.domain.LlmModelCatalogEntry;
import java.util.List;
import java.util.Optional;

/** OpenRouter 模型目录缓存的持久化边界。 */
public interface LlmModelCatalogRepository {

  List<LlmModelCatalogEntry> findAll(String query, boolean activeOnly);

  Optional<LlmModelCatalogEntry> findById(String modelId);

  long count();

  void replaceSnapshot(List<LlmModelCatalogEntry> entries);
}
