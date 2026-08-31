package com.shangan.ai.content.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 验证稳定版 LangChain4j 能调用 CPA/OpenAI-compatible Chat Completions。 */
class LangChain4jContentLanguageModelTest {

  private WireMockServer llm;

  @AfterEach
  void stopServer() {
    if (llm != null && llm.isRunning()) llm.stop();
  }

  @Test
  void returnsTextAndUpstreamTokenUsage() {
    llm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    llm.start();
    llm.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id":"chat-1","object":"chat.completion","created":1,
                          "model":"provider/model-1",
                          "choices":[{"index":0,"message":{"role":"assistant","content":"# 摘要"},"finish_reason":"stop"}],
                          "usage":{"prompt_tokens":120,"completion_tokens":20,"total_tokens":140}
                        }
                        """)));
    var client = new LangChain4jContentLanguageModel();
    var configuration =
        new RuntimeIntegrationSettings.Llm(
            llm.baseUrl() + "/v1", "llm-secret", "provider/model-1", 131072, 8192, 60);

    var result = client.generate("只输出中文", "课程全文", configuration, false);

    assertThat(result.text()).isEqualTo("# 摘要");
    assertThat(result.promptTokens()).isEqualTo(120);
    assertThat(result.completionTokens()).isEqualTo(20);
    llm.verify(
        postRequestedFor(urlEqualTo("/v1/chat/completions"))
            .withRequestBody(containing("provider/model-1"))
            .withRequestBody(containing("课程全文")));
  }
}
