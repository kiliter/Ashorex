package com.shangan.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.junit5.WireMockExtension.newInstance;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shangan.ai.application.AiChatEngine;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import dev.langchain4j.invocation.InvocationParameters;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证新 AI 流会使用管理后台最后保存的 LLM 地址、密钥和模型。 */
@SpringBootTest
class RuntimeAiConfigurationIntegrationTest {
  @RegisterExtension
  static WireMockExtension llm = newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir static Path databaseDirectory;

  @Autowired RuntimeIntegrationSettingsService settings;
  @Autowired AiChatEngine engine;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("runtime-ai.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @Test
  void newStreamUsesLatestSavedLlmSnapshot() throws Exception {
    llm.stubFor(
        post(urlEqualTo("/first/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer first-key"))
            .willReturn(streamingResponse("first-model", "旧配置回答")));
    llm.stubFor(
        post(urlEqualTo("/second/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer second-key"))
            .willReturn(streamingResponse("second-model", "新配置回答")));

    settings.save(snapshot(llm.baseUrl() + "/first", "first-key", "first-model"));
    TestListener first = stream("memory-first");
    assertThat(first.text()).isEqualTo("旧配置回答");

    settings.save(snapshot(llm.baseUrl() + "/second", "second-key", "second-model"));
    TestListener second = stream("memory-second");
    assertThat(second.text()).isEqualTo("新配置回答");
    assertThat(second.model()).isEqualTo("second-model");
    llm.verify(postRequestedFor(urlEqualTo("/second/chat/completions")));
  }

  private TestListener stream(String memoryId) throws InterruptedException {
    TestListener listener = new TestListener();
    engine.stream(memoryId, "你好", InvocationParameters.from(java.util.Map.of()), listener);
    assertThat(listener.await()).as("AI 流应在测试超时前结束").isTrue();
    assertThat(listener.error()).isNull();
    return listener;
  }

  private ResponseDefinitionBuilder streamingResponse(String model, String content) {
    String body =
        "data: {\"id\":\"chat-1\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\""
            + model
            + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
            + content
            + "\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"chat-1\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\""
            + model
            + "\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\n"
            + "data: [DONE]\n\n";
    return com.github.tomakehurst.wiremock.client.WireMock.ok()
        .withHeader("Content-Type", "text/event-stream")
        .withBody(body);
  }

  private RuntimeIntegrationSettings snapshot(String baseUrl, String apiKey, String model) {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby("", "", ""),
        new RuntimeIntegrationSettings.Llm(baseUrl, apiKey, model, 16_000, 0.2, 30),
        new RuntimeIntegrationSettings.Asr("", "", "", 120),
        new RuntimeIntegrationSettings.Mcp("", "", "web_search,web_extract", 20),
        0);
  }

  /** 收集异步 TokenStream 的终态，避免使用任意 sleep。 */
  private static final class TestListener implements AiChatEngine.Listener {
    private final StringBuilder text = new StringBuilder();
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile String model;
    private volatile Throwable error;

    @Override
    public void onToolStarted(String name) {}

    @Override
    public void onToolCompleted(String name, String result) {}

    @Override
    public void onDelta(String value) {
      text.append(value);
    }

    @Override
    public void onComplete(String modelName, int inputTokens, int outputTokens) {
      model = modelName;
      completed.countDown();
    }

    @Override
    public void onError(Throwable failure) {
      error = failure;
      completed.countDown();
    }

    private boolean await() throws InterruptedException {
      return completed.await(5, TimeUnit.SECONDS);
    }

    private String text() {
      return text.toString();
    }

    private String model() {
      return model;
    }

    private Throwable error() {
      return error;
    }
  }
}
