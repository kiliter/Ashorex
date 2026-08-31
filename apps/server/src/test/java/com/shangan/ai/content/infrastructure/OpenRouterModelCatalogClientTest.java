package com.shangan.ai.content.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 验证 OpenRouter 模型目录字段映射，不把目录地址用于实际 LLM 推理。 */
class OpenRouterModelCatalogClientTest {

  private WireMockServer openRouter;

  @AfterEach
  void stopServer() {
    if (openRouter != null && openRouter.isRunning()) openRouter.stop();
  }

  @Test
  void mapsContextOutputTokenizerAndSupportedParameters() {
    openRouter = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    openRouter.start();
    openRouter.stubFor(
        get(urlEqualTo("/api/v1/models"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"data":[{
                          "id":"provider/model-1",
                          "name":"Model One",
                          "context_length":131072,
                          "top_provider":{"max_completion_tokens":8192},
                          "architecture":{"tokenizer":"Other"},
                          "supported_parameters":["response_format","structured_outputs"]
                        }]}
                        """)));
    var client =
        new OpenRouterModelCatalogClient(
            new ObjectMapper(), openRouter.baseUrl() + "/api/v1/models");

    var model = client.fetch("catalog-secret", Instant.ofEpochMilli(1000)).getFirst();

    assertThat(model.modelId()).isEqualTo("provider/model-1");
    assertThat(model.contextLength()).isEqualTo(131072);
    assertThat(model.maxCompletionTokens()).isEqualTo(8192);
    assertThat(model.tokenizer()).isEqualTo("Other");
    assertThat(model.supportedParametersJson()).contains("structured_outputs");
    openRouter.verify(
        getRequestedFor(urlEqualTo("/api/v1/models"))
            .withHeader("Authorization", equalTo("Bearer catalog-secret")));
  }
}
