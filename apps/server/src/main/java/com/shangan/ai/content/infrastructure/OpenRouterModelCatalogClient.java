package com.shangan.ai.content.infrastructure;

import com.shangan.ai.content.domain.LlmModelCatalogEntry;
import com.shangan.common.api.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 只访问固定 OpenRouter Models API，并映射任务预算需要的模型元数据。 */
@Component
public class OpenRouterModelCatalogClient {

  static final String MODELS_URL = "https://openrouter.ai/api/v1/models";

  private final ObjectMapper json;
  private final String modelsUrl;

  @Autowired
  public OpenRouterModelCatalogClient(ObjectMapper json) {
    this(json, MODELS_URL);
  }

  OpenRouterModelCatalogClient(ObjectMapper json, String modelsUrl) {
    this.json = json;
    this.modelsUrl = modelsUrl;
  }

  /** 获取完整目录快照；调用失败时由上层保留原缓存。 */
  public List<LlmModelCatalogEntry> fetch(String apiKey, Instant fetchedAt) {
    try {
      var requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(Duration.ofSeconds(15));
      requestFactory.setReadTimeout(Duration.ofSeconds(30));
      String body =
          RestClient.builder()
              .requestFactory(requestFactory)
              .build()
              .get()
              .uri(modelsUrl)
              .headers(
                  headers -> {
                    if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);
                  })
              .retrieve()
              .body(String.class);
      JsonNode data = json.readTree(body).path("data");
      List<LlmModelCatalogEntry> result = new ArrayList<>();
      for (JsonNode node : data) {
        String id = node.path("id").asText("").trim();
        int contextLength = node.path("context_length").asInt(0);
        if (id.isBlank() || contextLength <= 0) continue;
        int maxCompletion =
            Math.max(256, node.path("top_provider").path("max_completion_tokens").asInt(4096));
        result.add(
            new LlmModelCatalogEntry(
                id,
                node.path("name").asText(id),
                contextLength,
                maxCompletion,
                node.path("architecture").path("tokenizer").asText("unknown"),
                json.writeValueAsString(node.path("supported_parameters")),
                fetchedAt,
                true));
      }
      if (result.isEmpty()) throw failed();
      return List.copyOf(result);
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw failed();
    }
  }

  private BusinessException failed() {
    return new BusinessException(
        HttpStatus.BAD_GATEWAY, "OPENROUTER_REFRESH_FAILED", "模型目录刷新失败，已保留原有缓存");
  }
}
