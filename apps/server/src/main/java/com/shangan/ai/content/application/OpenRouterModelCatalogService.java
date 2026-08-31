package com.shangan.ai.content.application;

import com.shangan.ai.content.domain.LlmModelCatalogEntry;
import com.shangan.ai.content.infrastructure.LlmModelCatalogRepository;
import com.shangan.ai.content.infrastructure.OpenRouterModelCatalogClient;
import com.shangan.common.integration.IntegrationSettingsProvider;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** 在事务外获取 OpenRouter 目录，再用一个短事务替换 SQLite 缓存。 */
@Service
public class OpenRouterModelCatalogService {

  private final OpenRouterModelCatalogClient client;
  private final LlmModelCatalogRepository repository;
  private final IntegrationSettingsProvider settings;
  private final Clock clock;

  public OpenRouterModelCatalogService(
      OpenRouterModelCatalogClient client,
      LlmModelCatalogRepository repository,
      IntegrationSettingsProvider settings,
      Clock clock) {
    this.client = client;
    this.repository = repository;
    this.settings = settings;
    this.clock = clock;
  }

  /** 手动刷新目录；远端失败时异常发生在写事务前，旧缓存保持不变。 */
  public List<LlmModelCatalogEntry> refresh() {
    List<LlmModelCatalogEntry> fetched =
        client.fetch(settings.current().openRouter().apiKey(), clock.instant());
    repository.replaceSnapshot(fetched);
    return fetched;
  }

  public List<LlmModelCatalogEntry> search(String query) {
    return repository.findAll(query, true);
  }

  public long count() {
    return repository.count();
  }
}
